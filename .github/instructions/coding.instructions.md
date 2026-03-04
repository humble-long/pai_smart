---
applyTo: "**"
---

# PaiSmart 智能体编码指令

## 核心架构认知（必读）

本项目是企业级 RAG 知识库系统，存在两条关键数据流：

**上传链路**：`UploadService` → Kafka(`file-processing-topic1`) → `FileProcessingConsumer` → `ParseService`(Tika流式解析+MySQL) → `VectorizationService`(EmbeddingClient+ES)

**查询链路**：`ChatHandler`(WebSocket) → `HybridSearchService.searchWithPermission()` → 并行(Dense KNN + Sparse BM25) → `rrfFusion()` → `RerankClient` → `DeepSeekClient`(SSE流式)

## 权限过滤规则（不可省略）

所有 ES 查询必须携带 `filter` 权限块，包含 `userId`、`public:true`、`orgTag` 三路 `should`。缺少权限过滤是严重错误。参考 `HybridSearchService.denseSearch()` 的实现模式。用户有效 orgTag 通过 `OrgTagCacheService.getUserEffectiveOrgTags(username)` 从 Redis 获取（TTL 24h）。

## 文档唯一键约定

- 文件以 `fileMd5`（MD5哈希）唯一标识
- chunk 以 `fileMd5 + "#" + chunkId` 组合键去重
- `document_vectors`（MySQL）和 `knowledge_base`（ES index）是同一内容的两份存储，均需携带 `userId`/`orgTag`/`isPublic`

## 异常处理规范

业务异常统一抛出 `CustomException(message, HttpStatus)`，不使用裸 `RuntimeException`。底层技术异常（IO、ES、Kafka）可包装为 `RuntimeException` 后抛出上层。

## 线程与异步约定

混合检索的 Dense/Sparse 两路必须提交给 `HybridSearchService.searchExecutor`（专用线程池），**不得使用** `ForkJoinPool.commonPool()`。`DeepSeekClient.streamResponse()` 基于 WebFlux，回调链不能阻塞。

## 配置注入方式

`@Value` 字段不初始化默认值时，测试中用 `ReflectionTestUtils.setField()` 注入。关键配置：
- `${file.parsing.chunk-size}` 子切片大小（dev 默认 512 字符）
- `${embedding.api.batch-size}` 向量批次（默认 10，DashScope 限制）
- `${embedding.api.dimension}` 向量维度（默认 2048）

## 新增 ES 查询检查清单

1. 是否带了权限 filter（userId / public / orgTag）
2. 是否指定了 index：`knowledge_base`
3. KNN 查询的 `numCandidates` 不低于 `k`
4. 结果映射时过滤 `hit.source() != null`

## 文件解析扩展规范

新增文件类型支持需同步修改：
1. `FileTypeValidationService.SUPPORTED_DOCUMENT_EXTENSIONS` 白名单
2. `ParseService.parseAndSave()` 入口处的格式路由（如需专用解析器）

## 前端开发

包管理用 `pnpm`，位于 `frontend/` 目录。`pnpm install && pnpm dev` 启动。主应用在 `frontend/src/`，独立官网页在 `homepage/`。
