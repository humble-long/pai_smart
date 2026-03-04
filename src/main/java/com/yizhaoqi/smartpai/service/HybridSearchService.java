package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.model.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;

import jakarta.annotation.PreDestroy;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 混合搜索服务，结合文本匹配和向量相似度搜索
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private RerankClient rerankClient;

    /**
     * 混合检索专用线程池：核心2线程（对应双路并行），最大4线程，队列容量20。
     * 使用独立线程池隔离检索任务，避免占用公共 ForkJoinPool。
     */
    private final ExecutorService searchExecutor = new ThreadPoolExecutor(
            2,                              // 核心线程数（Dense + Sparse 两路）
            4,                              // 最大线程数
            60L, TimeUnit.SECONDS,          // 空闲线程存活时间
            new LinkedBlockingQueue<>(20),  // 有界队列，防止任务堆积
            r -> {
                Thread t = new Thread(r, "hybrid-search-" + System.nanoTime());
                t.setDaemon(true);          // 守护线程，随主进程退出
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时由调用方线程执行，不丢任务
    );

    @PreDestroy
    public void shutdownSearchExecutor() {
        searchExecutor.shutdown();
        try {
            if (!searchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                searchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            searchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 三阶段真混合检索：双路并行召回 → RRF 融合 → 重排序
     *
     * <pre>
     * 阶段1 并行双路召回（每路独立）
     *   - Dense  路：KNN 向量召回（纯语义，无关键字约束）
     *   - Sparse 路：BM25 关键字召回（精确词项匹配）
     *   两路均携带权限过滤，互不干扰，并发执行。
     *
     * 阶段2 RRF 融合 (Reciprocal Rank Fusion)
     *   score(d) = Σ 1/(k + rank(d))，k=60
     *   在两路名单中都排名靠前的文档获得最高融合分。
     *
     * 阶段3 重排序 (Cross-Encoder Reranking)
     *   将 RRF 候选送入 gte-rerank 模型精细打分，输出最终 topK 条。
     * </pre>
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始三阶段混合检索，查询: {}, 用户ID: {}, topK: {}", query, userId, topK);

        // 权限信息（两路复用）
        List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
        String userDbId = getUserDbId(userId);

        // 每路独立召回的候选窗口
        int recallK = topK * 10;

        // ── 阶段1：双路并行召回 ──────────────────────────────────────────────
        CompletableFuture<List<SearchResult>> denseFuture = CompletableFuture
                .supplyAsync(() -> denseSearch(query, userDbId, userEffectiveTags, recallK), searchExecutor)
                .exceptionally(ex -> {
                    logger.warn("Dense 向量检索失败（降级为空列表）: {}", ex.getMessage());
                    return Collections.emptyList();
                });

        CompletableFuture<List<SearchResult>> sparseFuture = CompletableFuture
                .supplyAsync(() -> sparseSearch(query, userDbId, userEffectiveTags, recallK), searchExecutor)
                .exceptionally(ex -> {
                    logger.warn("Sparse BM25 检索失败（降级为空列表）: {}", ex.getMessage());
                    return Collections.emptyList();
                });

        List<SearchResult> denseResults  = denseFuture.join();
        List<SearchResult> sparseResults = sparseFuture.join();
        logger.debug("双路召回完成 | Dense: {} 条, Sparse: {} 条", denseResults.size(), sparseResults.size());

        // 双路均返回空 → 直接返回空
        if (denseResults.isEmpty() && sparseResults.isEmpty()) {
            logger.warn("双路召回均为空，返回空结果");
            return Collections.emptyList();
        }

        // ── 阶段2：RRF 融合 ─────────────────────────────────────────────────
        int rrfCandidateSize = topK * 10; // 送入重排序的候选规模
        List<SearchResult> fused = rrfFusion(denseResults, sparseResults, rrfCandidateSize);
        logger.debug("RRF 融合完成，候选数量: {}", fused.size());

        // ── 阶段3：重排序 ───────────────────────────────────────────────────
        List<SearchResult> reranked = rerankClient.rerank(query, fused, topK);
        logger.debug("重排序完成，最终返回 {} 条", reranked.size());

        attachFileNames(reranked);
        return reranked;
    }

    /**
     * 阶段1-A：Dense 向量检索（纯 KNN，无关键字约束）
     * 通过余弦相似度捕捉语义相关文档，包含同义、近义表达。
     */
    private List<SearchResult> denseSearch(String query, String userDbId,
                                           List<String> userEffectiveTags, int recallK) {
        logger.debug("Dense 检索开始，recallK: {}", recallK);
        List<Float> queryVector = embedToVectorList(query);
        if (queryVector == null) {
            throw new RuntimeException("向量生成失败，无法执行 Dense 检索");
        }

        try {
            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base");
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK)
                );
                // 仅携带权限过滤，不加 BM25 must（纯语义路）
                s.query(q -> q.bool(b -> b
                        .filter(f -> f.bool(bf -> {
                            bf.should(s1 -> s1.term(t -> t.field("userId").value(userDbId)));
                            bf.should(s2 -> s2.term(t -> t.field("public").value(true)));
                            if (!userEffectiveTags.isEmpty()) {
                                if (userEffectiveTags.size() == 1) {
                                    bf.should(s3 -> s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0))));
                                } else {
                                    bf.should(s3 -> s3.bool(inner -> {
                                        userEffectiveTags.forEach(tag -> inner.should(
                                                sh -> sh.term(t -> t.field("orgTag").value(tag))));
                                        return inner;
                                    }));
                                }
                            }
                            return bf;
                        }))
                ));
                s.size(recallK);
                return s;
            }, EsDocument.class);

            List<SearchResult> results = response.hits().hits().stream()
                    .filter(hit -> hit.source() != null)
                    .map(hit -> new SearchResult(
                            hit.source().getFileMd5(), hit.source().getChunkId(),
                            hit.source().getTextContent(), hit.score(),
                            hit.source().getUserId(), hit.source().getOrgTag(),
                            hit.source().isPublic()))
                    .collect(Collectors.toList());

            logger.debug("Dense 检索完成，命中 {} 条", results.size());
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Dense 检索执行失败", e);
        }
    }

    /**
     * 阶段1-B：Sparse BM25 检索（纯关键字，无向量）
     * 通过词项精确匹配捕捉包含特定专有名词、型号的文档。
     */
    private List<SearchResult> sparseSearch(String query, String userDbId,
                                            List<String> userEffectiveTags, int recallK) {
        logger.debug("Sparse 检索开始，recallK: {}", recallK);
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> b
                            .must(m -> m.match(ma -> ma.field("textContent").query(query)))
                            .filter(f -> f.bool(bf -> {
                                bf.should(s1 -> s1.term(t -> t.field("userId").value(userDbId)));
                                bf.should(s2 -> s2.term(t -> t.field("public").value(true)));
                                if (!userEffectiveTags.isEmpty()) {
                                    if (userEffectiveTags.size() == 1) {
                                        bf.should(s3 -> s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0))));
                                    } else {
                                        bf.should(s3 -> s3.bool(inner -> {
                                            userEffectiveTags.forEach(tag -> inner.should(
                                                    sh -> sh.term(t -> t.field("orgTag").value(tag))));
                                            return inner;
                                        }));
                                    }
                                }
                                return bf;
                            }))
                    ))
                    .size(recallK),
                    EsDocument.class
            );

            List<SearchResult> results = response.hits().hits().stream()
                    .filter(hit -> hit.source() != null)
                    .map(hit -> new SearchResult(
                            hit.source().getFileMd5(), hit.source().getChunkId(),
                            hit.source().getTextContent(), hit.score(),
                            hit.source().getUserId(), hit.source().getOrgTag(),
                            hit.source().isPublic()))
                    .collect(Collectors.toList());

            logger.debug("Sparse 检索完成，命中 {} 条", results.size());
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Sparse 检索执行失败", e);
        }
    }

    /**
     * 阶段2：RRF 融合 (Reciprocal Rank Fusion)
     *
     * <pre>
     * RRF_score(d) = Σ_i  1 / (k + rank_i(d))
     * k = 60（标准值，避免高排名对极端情况的惩罚过重）
     * </pre>
     *
     * 在两路名单中均出现且排名靠前的文档将获得最高融合分。
     * 文档去重以 fileMd5#chunkId 为唯一键。
     */
    private List<SearchResult> rrfFusion(List<SearchResult> denseList,
                                         List<SearchResult> sparseList, int topN) {
        final int k = 60;
        // 使用 LinkedHashMap 保持插入顺序，score 累加
        Map<String, Double>       scores = new LinkedHashMap<>();
        Map<String, SearchResult> docMap = new LinkedHashMap<>();

        // 处理 Dense 路（索引 i 对应排名 i+1）
        for (int i = 0; i < denseList.size(); i++) {
            SearchResult r = denseList.get(i);
            String key = r.getFileMd5() + "#" + r.getChunkId();
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
            docMap.putIfAbsent(key, r);
        }

        // 处理 Sparse 路
        for (int i = 0; i < sparseList.size(); i++) {
            SearchResult r = sparseList.get(i);
            String key = r.getFileMd5() + "#" + r.getChunkId();
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
            docMap.putIfAbsent(key, r);
        }

        // 按 RRF 分降序排列，取前 topN
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .map(entry -> {
                    SearchResult r = docMap.get(entry.getKey());
                    r.setScore(entry.getValue()); // 用 RRF 分覆盖原始分
                    return r;
                })
                .collect(Collectors.toList());
    }

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.debug("开始混合检索，查询: {}, topK: {}", query, topK);
            logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query);
            
            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearch(query, topK);
            }

            SearchResponse<EsDocument> response = esClient.search(s -> {
                        s.index("knowledge_base");
                        int recallK = topK * 30;
                        s.knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK)
                        );

                        // 过滤仅保留包含关键词的文本
                        s.query(q -> q.match(m -> m.field("textContent").query(query)));

                        // rescore BM25
                        s.rescore(r -> r
                                .windowSize(recallK)
                                .query(rq -> rq
                                        .queryWeight(0.2d)
                                        .rescoreQueryWeight(1.0d)
                                        .query(rqq -> rqq.match(m -> m
                                                .field("textContent")
                                                .query(query)
                                                .operator(Operator.And)
                                        ))
                                )
                        );
                        s.size(topK);
                        return s;
                    }, EsDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score()
                        );
                    })
                    .toList();
        } catch (Exception e) {
            logger.error("搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    /**
     * 仅使用文本匹配的搜索方法
     */
    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index("knowledge_base")
                .query(q -> q
                        .match(m -> m
                                .field("textContent")
                                .query(query)
                        )
                )
                .size(topK),
                EsDocument.class
        );

        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score()
                    );
                })
                .toList();
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text));
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }
    
    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}", user.getUsername());
            }
            
            // 通过orgTagCacheService获取用户的有效标签集合
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList(); // 返回空列表作为默认值
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
                return userIdLong.toString(); // 如果输入已经是数字ID，直接返回
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}, ID: {}", user.getUsername(), user.getId());
                return user.getId().toString(); // 返回用户的数据库ID
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            // 收集所有唯一的 fileMd5
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new java.util.ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName));
            // 填充文件名
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }
}
