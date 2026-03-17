package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Value("${file.parsing.chunk-size}")
    private int chunkSize;

    @Value("${file.parsing.parent-chunk-size:1048576}")
    private int parentChunkSize;

    @Value("${file.parsing.buffer-size:8192}")
    private int bufferSize;

    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    @Value("${file.parsing.overlap-size:100}")
    private int overlapSize;

    @Value("${file.parsing.excel.rows-per-chunk:10}")
    private int excelRowsPerChunk;

    @Value("${file.parsing.excel.overlap-rows:2}")
    private int excelOverlapRows;

    /**
     * 子切片大小（字符数）。子切片入 ES 索引用于精准检索，命中后回捞父切片（chunkSize）传给 LLM。
     */
    @Value("${file.parsing.child-chunk-size:128}")
    private int childChunkSize;

    /**
     * 子切片滑动窗口重叠大小（字符数）。每个子切片开头会重复前一个子切片末尾的若干字符，
     * 防止句子在切片边界被截断。有效步长 = childChunkSize - childOverlapSize。
     */
    @Value("${file.parsing.child-overlap-size:32}")
    private int childOverlapSize;

    public ParseService() {
        // 无需初始化，StandardTokenizer是静态方法
    }

    /**
     * 以流式方式解析文件，将内容分块并保存到数据库，以避免OOM。
     * 采用"父文档-子切片"策略。
     *
     * @param fileMd5    文件的MD5哈希值，用于唯一标识文件
     * @param fileStream 文件输入流，用于读取文件内容
     * @param userId     上传用户ID
     * @param orgTag     组织标签
     * @param isPublic   是否公开
     * @throws IOException   如果文件读取过程中发生错误
     * @throws TikaException 如果文件解析过程中发生错误
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String fileName, String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("开始流式解析文件，fileMd5: {}, fileName: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, fileName, userId, orgTag, isPublic);

        checkMemoryThreshold();

        // 根据文件扩展名路由到不同解析器
        if (isExcelFile(fileName)) {
            logger.info("检测到 Excel 文件，使用表格专用切片逻辑");
            parseAndSaveExcel(fileMd5, fileStream, userId, orgTag, isPublic);
            return;
        }

        // 非 Excel 文件：原有 Tika 流式解析逻辑
        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            // 创建一个流式处理器，它会在内部处理父块的切分和子块的保存
            StreamingContentHandler handler = new StreamingContentHandler(fileMd5, userId, orgTag, isPublic);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            // Tika的parse方法会驱动整个流式处理过程
            // 当handler的characters方法接收到足够数据时，会触发分块、切片和保存
            parser.parse(bufferedStream, handler, metadata, context);

            logger.info("文件流式解析和入库完成，fileMd5: {}", fileMd5);

        } catch (SAXException e) {
            logger.error("文档解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        }
    }

    /**
     * 兼容旧版本的解析方法
     */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        // 使用默认值调用新方法
        parseAndSave(fileMd5, fileStream, null, "unknown", "DEFAULT", false);
    }

    // -----------------------------------------------------------------------
    // Excel 专用解析逻辑
    // -----------------------------------------------------------------------

    /** 判断是否为 Excel 文件（.xls / .xlsx） */
    private boolean isExcelFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".xls") || lower.endsWith(".xlsx");
    }

    /**
     * Excel 表格专用切片：每行携带表头重建为自然语言，避免表头与数据分离问题。
     * <p>
     * 切片策略：
     * - 以"行组"为单位，每 {@code excelRowsPerChunk} 行生成一个 chunk。
     * - 相邻 chunk 重叠 {@code excelOverlapRows} 行，保证跨 chunk 查询时上下文完整。
     * - 每行格式：【Sheet名】列名1：值1，列名2：值2。
     * </p>
     */
    private void parseAndSaveExcel(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(fileStream)) {
            int totalChunksSaved = 0;

            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);
                String sheetName = sheet.getSheetName();
                logger.info("解析 Sheet[{}]: {}", sheetIdx, sheetName);

                // 读取表头（第一行）
                Row headerRow = sheet.getRow(sheet.getFirstRowNum());
                if (headerRow == null) {
                    logger.warn("Sheet[{}] 无表头，跳过", sheetName);
                    continue;
                }
                List<String> headers = new ArrayList<>();
                for (Cell cell : headerRow) {
                    headers.add(getCellValueAsString(cell, workbook));
                }
                // 过滤空列名（末尾的空列通常是 Excel 占位符）
                int lastNonEmptyHeader = headers.size() - 1;
                while (lastNonEmptyHeader >= 0 && headers.get(lastNonEmptyHeader).isBlank()) {
                    lastNonEmptyHeader--;
                }
                if (lastNonEmptyHeader < 0) {
                    logger.warn("Sheet[{}] 表头全为空，跳过", sheetName);
                    continue;
                }
                headers = headers.subList(0, lastNonEmptyHeader + 1);
                logger.debug("Sheet[{}] 有效列数: {}, 表头: {}", sheetName, headers.size(), headers);

                // 收集所有有效数据行的自然语言描述
                List<String> rowDescriptions = new ArrayList<>();
                int firstDataRow = sheet.getFirstRowNum() + 1;
                for (int rowIdx = firstDataRow; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                    Row row = sheet.getRow(rowIdx);
                    if (row == null || isRowEmpty(row, headers.size())) continue;

                    StringBuilder rowDesc = new StringBuilder();
                    rowDesc.append("【").append(sheetName).append("】");
                    boolean hasData = false;
                    for (int colIdx = 0; colIdx < headers.size(); colIdx++) {
                        String header = headers.get(colIdx);
                        if (header.isBlank()) continue;
                        Cell cell = row.getCell(colIdx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        String value = cell != null ? getCellValueAsString(cell, workbook) : "";
                        if (!value.isBlank()) {
                            rowDesc.append(header).append("：").append(value).append("，");
                            hasData = true;
                        }
                    }
                    if (!hasData) continue;
                    // 去掉末尾逗号，改为句号
                    String desc = rowDesc.toString().replaceAll("，$", "。");
                    rowDescriptions.add(desc);
                }
                logger.info("Sheet[{}] 共 {} 条有效数据行", sheetName, rowDescriptions.size());

                // 按滑动窗口生成带重叠的 chunk
                totalChunksSaved = buildExcelChunks(
                        fileMd5, rowDescriptions, userId, orgTag, isPublic, totalChunksSaved);
            }
            logger.info("Excel 解析完成，fileMd5: {}, 总 chunk 数: {}", fileMd5, totalChunksSaved);
        } catch (Exception e) {
            logger.error("Excel 解析失败，fileMd5: {}", fileMd5, e);
            throw new IOException("Excel 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将行描述列表按滑动窗口切成带重叠的 chunk 并保存。
     * <p>
     * 窗口步长 = {@code excelRowsPerChunk - excelOverlapRows}，
     * 保证每个 chunk 开头包含 {@code excelOverlapRows} 行上一个 chunk 末尾的内容。
     * </p>
     *
     * @return 保存后总的 chunk 数量
     */
    private int buildExcelChunks(String fileMd5, List<String> rowDescs,
            String userId, String orgTag, boolean isPublic, int startId) {
        if (rowDescs.isEmpty()) return startId;

        int step = Math.max(1, excelRowsPerChunk - excelOverlapRows);
        int total = startId;

        for (int i = 0; i < rowDescs.size(); i += step) {
            int end = Math.min(i + excelRowsPerChunk, rowDescs.size());
            List<String> group = rowDescs.subList(i, end);

            // 父切片：多行合并的完整语义块，供 LLM 见到完整上下文
            total++;
            int parentId = total;
            String parentContent = String.join("\n", group);
            saveDocumentVector(fileMd5, parentId, parentContent, null, userId, orgTag, isPublic);

            // 子切片：每行单独存储，精细粒度入 ES 向量索引提升检索命中率
            for (String rowDesc : group) {
                total++;
                saveDocumentVector(fileMd5, total, rowDesc, parentId, userId, orgTag, isPublic);
            }

            if (end >= rowDescs.size()) break;
        }
        return total;
    }

    /** 将单元格值统一转为字符串（处理数字、日期、公式等类型） */
    private String getCellValueAsString(Cell cell, Workbook workbook) {
        if (cell == null) return "";
        DataFormatter formatter = new DataFormatter();
        return formatter.formatCellValue(cell).trim();
    }

    /** 判断一行是否全为空（只检查有效列范围内） */
    private boolean isRowEmpty(Row row, int colCount) {
        if (row == null) return true;
        for (int i = 0; i < colCount; i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String val = new DataFormatter().formatCellValue(cell).trim();
                if (!val.isEmpty()) return false;
            }
        }
        return true;
    }

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double memoryUsage = (double) usedMemory / maxMemory;

        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {:.2f}%, 触发垃圾回收", memoryUsage * 100);
            System.gc();

            // 重新检查
            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;

            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: " +
                        String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }

    /**
     * 内部流式内容处理器，实现了父子文档切分策略的核心逻辑。
     * Tika解析器会调用characters方法，当累积的文本达到"父块"大小时，
     * 就触发processParentChunk方法，进行"子切片"的生成和入库。
     */
    private class StreamingContentHandler extends BodyContentHandler {
        // 用于累积文本内容
        private final StringBuilder buffer = new StringBuilder();
        private final String fileMd5;
        private final String userId;
        private final String orgTag;
        private final boolean isPublic;
        // 已保存的子切片数量
        private int savedChunkCount = 0;

        public StreamingContentHandler(String fileMd5, String userId, String orgTag, boolean isPublic) {
            super(-1); // 禁用Tika的内部写入限制，我们自己管理缓冲区
            this.fileMd5 = fileMd5;
            this.userId = userId;
            this.orgTag = orgTag;
            this.isPublic = isPublic;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= parentChunkSize) {
                processParentChunk();
            }
        }

        @Override
        public void endDocument() {
            // 处理文档末尾剩余的最后一部分内容
            if (buffer.length() > 0) {
                processParentChunk();
            }
        }

        private void processParentChunk() {
            String parentChunkText = buffer.toString();
            logger.debug("处理父文本块，大小: {} bytes", parentChunkText.length());

            // Level-1：将 1MB 缓冲区切割为语义完整的父切片（chunkSize 字符，默认 512）
            List<String> parentTexts = ParseService.this.splitTextIntoChunksWithSemantics(parentChunkText, chunkSize);

            for (String parentText : parentTexts) {
                // 存父切片（parentChunkId = null）
                this.savedChunkCount++;
                int parentId = this.savedChunkCount;
                ParseService.this.saveDocumentVector(fileMd5, parentId, parentText, null, userId, orgTag, isPublic);

                // Level-2：将父切片按滑动窗口切为子切片（childChunkSize 字符，步长 = size - overlap）——打入 ES 索引
                // 父切片已完成语义对齐，子切片等宽截取+overlap防止句子被截断
                int childSize = ParseService.this.childChunkSize;
                int childOverlap = ParseService.this.childOverlapSize;
                int effectiveStep = Math.max(1, childSize - childOverlap);
                for (int i = 0; i < parentText.length(); i += effectiveStep) {
                    int end = Math.min(i + childSize, parentText.length());
                    String childText = parentText.substring(i, end);
                    this.savedChunkCount++;
                    ParseService.this.saveDocumentVector(fileMd5, this.savedChunkCount, childText, parentId, userId, orgTag, isPublic);
                    if (end >= parentText.length()) break;  // 最后一段直接退出，避免重复保存末尾短片段
                }
            }
            logger.info("父切片处理完成，本批 {} 个父切片", parentTexts.size());

            // 清空缓冲区，为下一个父块做准备
            buffer.setLength(0);
        }
    }

    /**
     * 保存单个 DocumentVector，支持父子切片关联。
     *
     * @param parentChunkId null = 该切片本身是父切片；有值 = 子切片，指向父切片 chunkId
     */
    private void saveDocumentVector(String fileMd5, int chunkId, String text,
            Integer parentChunkId, String userId, String orgTag, boolean isPublic) {
        var vector = new DocumentVector();
        vector.setFileMd5(fileMd5);
        vector.setChunkId(chunkId);
        vector.setTextContent(text);
        vector.setParentChunkId(parentChunkId);
        vector.setUserId(userId);
        vector.setOrgTag(orgTag);
        vector.setPublic(isPublic);
        documentVectorRepository.save(vector);
    }

    /**
     * 智能文本分割，保持语义完整性
     */
    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 按段落分割
        String[] paragraphs = text.split("\n\n+");

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            // 如果单个段落超过chunk大小，需要进一步分割
            if (paragraph.length() > chunkSize) {
                // 先保存当前chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                // 按句子分割长段落
                List<String> sentenceChunks = splitLongParagraph(paragraph, chunkSize);
                chunks.addAll(sentenceChunks);
            }
            // 如果添加这个段落会超过chunk大小
            else if (currentChunk.length() + paragraph.length() > chunkSize) {
                // 保存当前chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                }
                // 开始新chunk
                currentChunk = new StringBuilder(paragraph);
            }
            // 可以添加到当前chunk
            else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }
        }

        // 添加最后一个chunk
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return applyOverlap(chunks, overlapSize);
    }

    /**
     * 为相邻 chunk 添加滑动窗口重叠，防止跨边界信息被割裂。
     * chunk[i] 末尾 overlapSize 个字符会被追加到 chunk[i+1] 的开头。
     *
     * @param chunks      原始分块列表
     * @param overlapSize 重叠窗口大小（字符数），0 表示不重叠
     * @return 带重叠的分块列表
     */
    private List<String> applyOverlap(List<String> chunks, int overlapSize) {
        if (overlapSize <= 0 || chunks.size() <= 1) {
            return chunks;
        }
        List<String> result = new ArrayList<>(chunks.size());
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            // 取前一个 chunk 末尾 overlapSize 字符作为上下文前缀
            String overlap = prev.length() > overlapSize
                    ? prev.substring(prev.length() - overlapSize)
                    : prev;
            result.add(overlap + chunks.get(i));
        }
        return result;
    }

    /**
     * 分割长段落，按句子边界
     */
    private List<String> splitLongParagraph(String paragraph, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 按句子分割
        String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");

        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                // 如果单个句子太长，按词分割
                if (sentence.length() > chunkSize) {
                    chunks.addAll(splitLongSentence(sentence, chunkSize));
                } else {
                    currentChunk.append(sentence);
                }
            } else {
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 使用HanLP智能分割超长句子，中文按语义切割
     */
    private List<String> splitLongSentence(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        try {
            // 使用HanLP StandardTokenizer进行分词
            List<Term> termList = StandardTokenizer.segment(sentence);

            StringBuilder currentChunk = new StringBuilder();
            for (Term term : termList) {
                String word = term.word;

                // 如果添加这个词会超过chunk大小限制，且当前chunk不为空
                if (currentChunk.length() + word.length() > chunkSize && !currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }

                currentChunk.append(word);
            }

            if (!currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
            }

            logger.debug("HanLP智能分词成功，原文长度: {}, 分词数: {}, 分块数: {}",
                    sentence.length(), termList.size(), chunks.size());

        } catch (Exception e) {
            logger.warn("HanLP分词异常: {}, 使用字符分割作为备用方案", e.getMessage());
            chunks = splitByCharacters(sentence, chunkSize);
        }

        return chunks;
    }

    /**
     * 备用方案：按字符分割
     */
    private List<String> splitByCharacters(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);

            if (currentChunk.length() + 1 > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }

            currentChunk.append(c);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }
}
