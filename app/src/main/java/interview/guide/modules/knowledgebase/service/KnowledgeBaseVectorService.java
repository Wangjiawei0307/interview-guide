package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.ParsedDocumentBlock;
import interview.guide.infrastructure.file.ParsedKnowledgeDocument;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 知识库向量化与检索服务。
 *
 * <p>该服务承接 Redis Stream 中的向量化任务，将解析层产出的结构化文档块转换为
 * Spring AI {@link Document} 后写入 PgVector。切分策略不是对所有文件做统一固定长度切分，
 * 而是按内容块类型处理：
 *
 * <ul>
 *   <li>Text：按段落递归切分，并保留 overlap，降低跨 chunk 语义断裂。</li>
 *   <li>Table：作为独立 chunk 写入，避免表头、字段和值被拆散。</li>
 *   <li>Image：当前写入图片基础 metadata，占位等待 OCR/caption 扩展。</li>
 * </ul>
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {

    /**
     * DashScope Embedding 批量接口单批数量较小，向量写入时按小批次提交更稳。
     */
    private static final int MAX_BATCH_SIZE = 10;

    /**
     * 文本块目标长度，约等价于几百 token，避免单个 chunk 过长导致召回噪声过大。
     */
    private static final int TARGET_CHARS = 1_800;

    /**
     * 相邻文本 chunk 的重叠长度，用来保留跨段落边界的上下文。
     */
    private static final int OVERLAP_CHARS = 220;

    private final VectorStore vectorStore;
    private final VectorRepository vectorRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentPayloadCodec documentPayloadCodec;

    public KnowledgeBaseVectorService(
        VectorStore vectorStore,
        VectorRepository vectorRepository,
        KnowledgeBaseRepository knowledgeBaseRepository,
        KnowledgeDocumentPayloadCodec documentPayloadCodec
    ) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentPayloadCodec = documentPayloadCodec;
    }

    /**
     * 重新构建指定知识库的向量数据。
     *
     * <p>流程是：解码结构化文档载荷、删除旧向量、构造 chunk、分批写入 PgVector，最后回写
     * chunkCount。这里兼容旧版纯文本 payload，避免 Redis Stream 中的历史任务无法消费。
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content) {
        log.info("Start vectorizing knowledge base: kbId={}, payloadLength={}",
            knowledgeBaseId, content == null ? 0 : content.length());
        try {
            ParsedKnowledgeDocument parsedDocument = documentPayloadCodec.decodeOrLegacy(content);
            deleteByKnowledgeBaseId(knowledgeBaseId);

            List<Document> chunks = buildChunkDocuments(knowledgeBaseId, parsedDocument);
            if (chunks.isEmpty()) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "没有可向量化的文档块");
            }

            int totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
            log.info("Document chunking completed: kbId={}, sourceType={}, blocks={}, chunks={}, batches={}",
                knowledgeBaseId, parsedDocument.sourceType(), parsedDocument.blocks().size(), totalChunks, batchCount);

            // 向量模型和 PgVector 写入都走批处理，降低单次请求过大带来的超时风险。
            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);
                log.debug("Vectorizing batch {}/{}: chunks {}-{}", i + 1, batchCount, start + 1, end);
                vectorStore.add(batch);
            }

            updateChunkCount(knowledgeBaseId, totalChunks);
            log.info("Knowledge base vectorization completed: kbId={}, chunks={}", knowledgeBaseId, totalChunks);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Knowledge base vectorization failed: kbId={}", knowledgeBaseId, e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化知识库失败: " + e.getMessage());
        }
    }

    /**
     * 按知识库范围做相似度检索。
     *
     * <p>正常路径通过 PgVector metadata filter 过滤 kb_id；如果当前 PgVector filter 语法或版本
     * 不兼容，则降级到本地过滤，保证问答功能仍可用。
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("Vector similarity search: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);

        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }

            List<Document> limitedResults = results.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("Vector similarity search completed: results={}", limitedResults.size());
            return limitedResults;
        } catch (Exception e) {
            log.warn("Vector pre-filter search failed, fallback to local filtering: {}", e.getMessage());
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    /**
     * 将结构化文档块转换成最终入库的向量文档。
     *
     * <p>表格和图片保持单块入库，文本块再进入递归切分。所有 chunk 都会补充来源、块类型、
     * 父块序号和知识库 ID 等 metadata，便于检索过滤和召回解释。
     */
    private List<Document> buildChunkDocuments(Long knowledgeBaseId, ParsedKnowledgeDocument parsedDocument) {
        List<Document> documents = new ArrayList<>();
        int blockIndex = 0;
        int chunkIndex = 0;

        for (ParsedDocumentBlock block : parsedDocument.blocks()) {
            String blockContent = block.content() == null ? "" : block.content().trim();
            if (blockContent.isBlank()) {
                blockIndex++;
                continue;
            }

            Map<String, String> baseMetadata = new LinkedHashMap<>(block.metadata());
            baseMetadata.put("kb_id", knowledgeBaseId.toString());
            baseMetadata.putIfAbsent("source_type", parsedDocument.sourceType());
            baseMetadata.putIfAbsent("original_filename", parsedDocument.originalFilename());
            baseMetadata.put("block_type", block.type().name().toLowerCase());
            baseMetadata.put("parent_block_index", String.valueOf(blockIndex));

            if (block.type() == ParsedDocumentBlock.BlockType.TABLE) {
                // 表格不做普通文本切分，避免表头和数据行分离后破坏字段关系。
                baseMetadata.putIfAbsent("chunk_strategy", "table_standalone");
                documents.add(toDocument(blockContent, baseMetadata, chunkIndex++, 0));
            } else if (block.type() == ParsedDocumentBlock.BlockType.IMAGE) {
                // 图片先以基础 metadata 入库，接入 OCR/caption 后可替换为视觉文本。
                baseMetadata.putIfAbsent("chunk_strategy", "image_metadata_placeholder");
                documents.add(toDocument(blockContent, baseMetadata, chunkIndex++, 0));
            } else {
                // 文本块按段落递归切分，并针对不同来源打上策略标签。
                List<String> splitChunks = splitTextBlock(blockContent);
                String sourceType = baseMetadata.getOrDefault("source_type", parsedDocument.sourceType());
                baseMetadata.putIfAbsent("chunk_strategy", textChunkStrategy(sourceType));
                for (int partIndex = 0; partIndex < splitChunks.size(); partIndex++) {
                    documents.add(toDocument(splitChunks.get(partIndex), baseMetadata, chunkIndex++, partIndex));
                }
            }
            blockIndex++;
        }
        return documents;
    }

    /**
     * 创建 Spring AI Document，并补充 chunk 层面的顺序和长度信息。
     */
    private Document toDocument(
        String content,
        Map<String, String> metadata,
        int chunkIndex,
        int chunkPartIndex
    ) {
        Document document = new Document(content);
        Map<String, String> chunkMetadata = new LinkedHashMap<>(metadata);
        chunkMetadata.put("chunk_index", String.valueOf(chunkIndex));
        chunkMetadata.put("chunk_part_index", String.valueOf(chunkPartIndex));
        chunkMetadata.put("chunk_char_length", String.valueOf(content.length()));
        document.getMetadata().putAll(chunkMetadata);
        return document;
    }

    /**
     * 给文本 chunk 标注策略名，方便后续分析召回结果来自哪类切分方式。
     */
    private String textChunkStrategy(String sourceType) {
        return switch (sourceType) {
            case "word" -> "section_recursive_overlap";
            case "pdf" -> "layout_text_recursive_overlap";
            case "markdown" -> "markdown_recursive_overlap";
            default -> "recursive_overlap";
        };
    }

    /**
     * 文本递归切分：优先按段落聚合，超长段落再按句子边界切分，并保留 overlap。
     */
    private List<String> splitTextBlock(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isBlank()) {
            return List.of();
        }
        if (text.length() <= TARGET_CHARS) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String segment : splitParagraphs(text)) {
            if (segment.length() > TARGET_CHARS) {
                flushCurrent(chunks, current);
                chunks.addAll(splitLongSegment(segment));
                continue;
            }

            if (current.isEmpty()) {
                current.append(segment);
                continue;
            }

            if (current.length() + segment.length() + 2 <= TARGET_CHARS) {
                current.append("\n\n").append(segment);
            } else {
                String previousChunk = current.toString().trim();
                chunks.add(previousChunk);
                current.setLength(0);

                // 保留上一块尾部，减少答案所需信息刚好落在两个 chunk 边界的问题。
                String overlap = tailOverlap(previousChunk);
                if (!overlap.isBlank()) {
                    current.append(overlap).append("\n\n");
                }
                current.append(segment);
            }
        }
        flushCurrent(chunks, current);
        return chunks.stream()
            .map(String::trim)
            .filter(chunk -> !chunk.isBlank())
            .toList();
    }

    /**
     * 用空行识别自然段落，保留原文中的段落边界作为第一层语义边界。
     */
    private List<String> splitParagraphs(String text) {
        String[] rawSegments = text.split("\\n\\s*\\n");
        List<String> segments = new ArrayList<>();
        for (String rawSegment : rawSegments) {
            String segment = rawSegment.trim();
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments.isEmpty() ? List.of(text) : segments;
    }

    /**
     * 处理单个超长段落。优先找句末符号，找不到时才按字符窗口硬切。
     */
    private List<String> splitLongSegment(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(start + TARGET_CHARS, text.length());
            int end = hardEnd;
            if (hardEnd < text.length()) {
                int boundary = findBoundary(text, start, hardEnd);
                if (boundary > start) {
                    end = boundary;
                }
            }
            chunks.add(text.substring(start, end).trim());
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - OVERLAP_CHARS, start + 1);
        }
        return chunks;
    }

    /**
     * 从目标窗口末尾向前找自然边界，避免切出过短 chunk。
     */
    private int findBoundary(String text, int start, int end) {
        int min = start + Math.max(200, TARGET_CHARS / 2);
        for (int i = end - 1; i >= min; i--) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '。' || ch == '！' || ch == '？'
                || ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == '；') {
                return i + 1;
            }
        }
        return -1;
    }

    private void flushCurrent(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
            current.setLength(0);
        }
    }

    private String tailOverlap(String text) {
        if (text.length() <= OVERLAP_CHARS) {
            return text;
        }
        return text.substring(text.length() - OVERLAP_CHARS).trim();
    }

    /**
     * PgVector filter 表达式不可用时的兜底路径：扩大召回集合，再按 kb_id 本地过滤。
     */
    private List<Document> similaritySearchFallback(
        String query,
        List<Long> knowledgeBaseIds,
        int topK,
        double minScore
    ) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("Fallback vector search completed: results={}", results.size());
            return results;
        } catch (Exception e) {
            log.error("Vector similarity search failed", e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "向量搜索失败: " + e.getMessage());
        }
    }

    /**
     * Spring AI PgVectorStore 返回的 metadata 类型不一定固定，这里统一转成 Long 比较。
     */
    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long value ? value : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 生成 Spring AI filter expression，例如：kb_id in ['1', '2']。
     */
    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }

    /**
     * 删除指定知识库已有向量，重新向量化前先清理旧数据，避免同一知识库重复召回。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
        } catch (Exception e) {
            // 保持原有行为：删除失败只记录日志，避免删除接口影响调用方。
            log.error("Delete vector data failed: kbId={}", knowledgeBaseId, e);
        }
    }

    private void updateChunkCount(Long knowledgeBaseId, int chunkCount) {
        try {
            knowledgeBaseRepository.findById(knowledgeBaseId).ifPresent(kb -> {
                kb.setChunkCount(chunkCount);
                knowledgeBaseRepository.save(kb);
            });
        } catch (Exception e) {
            log.warn("Update knowledge base chunk count failed: kbId={}, chunkCount={}", knowledgeBaseId, chunkCount, e);
        }
    }
}