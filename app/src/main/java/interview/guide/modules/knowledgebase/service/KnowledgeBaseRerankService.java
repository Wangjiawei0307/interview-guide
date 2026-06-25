package interview.guide.modules.knowledgebase.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RAG 二阶段重排服务。
 *
 * <p>当前实现是轻量本地 rerank，不依赖额外模型服务：先保留向量召回的原始排序作为基础分，
 * 再结合 query-doc 关键词命中、完整短语命中、metadata 命中和 chunk 长度进行重排。
 * 后续如果接入 BGE Reranker、DashScope Rerank 或 LLM 打分，只需要替换该服务内部实现。
 */
@Slf4j
@Service
public class KnowledgeBaseRerankService {

    private final boolean enabled;
    private final double vectorWeight;
    private final double keywordWeight;
    private final double metadataWeight;
    private final double phraseWeight;

    public KnowledgeBaseRerankService(KnowledgeBaseQueryProperties queryProperties) {
        KnowledgeBaseQueryProperties.Rerank rerank = queryProperties.getRerank();
        this.enabled = rerank.isEnabled();
        this.vectorWeight = rerank.getVectorWeight();
        this.keywordWeight = rerank.getKeywordWeight();
        this.metadataWeight = rerank.getMetadataWeight();
        this.phraseWeight = rerank.getPhraseWeight();
    }

    public List<Document> rerank(String query, List<Document> documents, int limit) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        int finalLimit = normalizeLimit(limit, documents.size());
        if (!enabled || documents.size() == 1) {
            return documents.stream().limit(finalLimit).toList();
        }

        try {
            List<String> terms = extractTerms(query);
            int total = documents.size();
            List<RankedDocument> rankedDocuments = new ArrayList<>();

            for (int index = 0; index < documents.size(); index++) {
                Document document = documents.get(index);
                double score = scoreDocument(query, terms, document, index, total);
                rankedDocuments.add(new RankedDocument(document, score, index));
            }

            List<Document> reranked = rankedDocuments.stream()
                .sorted(Comparator
                    .comparingDouble(RankedDocument::score).reversed()
                    .thenComparingInt(RankedDocument::originalIndex))
                .limit(finalLimit)
                .map(RankedDocument::document)
                .toList();

            attachRerankMetadata(reranked, rankedDocuments);
            log.info("Rerank completed: candidates={}, returned={}, enabled={}",
                documents.size(), reranked.size(), enabled);
            return reranked;
        } catch (Exception e) {
            log.warn("Rerank failed, fallback to vector order: {}", e.getMessage(), e);
            return documents.stream().limit(finalLimit).toList();
        }
    }

    private double scoreDocument(
        String query,
        List<String> terms,
        Document document,
        int originalIndex,
        int total
    ) {
        String text = document.getText() == null ? "" : document.getText();
        String metadataText = metadataText(document.getMetadata());

        double vectorRankScore = total <= 1 ? 1.0 : 1.0 - (double) originalIndex / (total - 1);
        double keywordScore = termCoverageScore(terms, text);
        double metadataScore = termCoverageScore(terms, metadataText);
        double phraseScore = phraseScore(query, text + "\n" + metadataText);
        double lengthScore = lengthScore(text.length());
        double typeBoost = typeBoost(query, document.getMetadata());

        double weightedScore = vectorWeight * vectorRankScore
            + keywordWeight * keywordScore
            + metadataWeight * metadataScore
            + phraseWeight * phraseScore;
        return weightedScore * lengthScore + typeBoost;
    }

    private List<String> extractTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Set<String> terms = new LinkedHashSet<>();
        String lower = query.toLowerCase(Locale.ROOT);
        StringBuilder asciiToken = new StringBuilder();
        StringBuilder hanToken = new StringBuilder();

        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (isHan(ch)) {
                flushAsciiToken(terms, asciiToken);
                hanToken.append(ch);
            } else if (Character.isLetterOrDigit(ch) || ch == '+' || ch == '#' || ch == '_') {
                flushHanToken(terms, hanToken);
                asciiToken.append(ch);
            } else {
                flushAsciiToken(terms, asciiToken);
                flushHanToken(terms, hanToken);
            }
        }
        flushAsciiToken(terms, asciiToken);
        flushHanToken(terms, hanToken);

        String compact = compact(query);
        if (compact.length() >= 2 && compact.length() <= 30) {
            terms.add(compact.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(terms);
    }

    private void flushAsciiToken(Set<String> terms, StringBuilder token) {
        if (token.length() >= 2) {
            terms.add(token.toString());
        }
        token.setLength(0);
    }

    private void flushHanToken(Set<String> terms, StringBuilder token) {
        String value = token.toString();
        token.setLength(0);
        if (value.isBlank()) {
            return;
        }
        if (value.length() == 1) {
            terms.add(value);
            return;
        }
        if (value.length() <= 12) {
            terms.add(value);
        }
        for (int i = 0; i + 2 <= value.length(); i++) {
            terms.add(value.substring(i, i + 2));
        }
        for (int i = 0; i + 3 <= value.length(); i++) {
            terms.add(value.substring(i, i + 3));
        }
    }

    private double termCoverageScore(List<String> terms, String content) {
        if (terms.isEmpty() || content == null || content.isBlank()) {
            return 0.0;
        }
        String normalizedContent = normalizeForMatch(content);
        double matched = 0.0;
        double occurrence = 0.0;
        for (String term : terms) {
            String normalizedTerm = normalizeForMatch(term);
            if (normalizedTerm.isBlank()) {
                continue;
            }
            int count = countOccurrences(normalizedContent, normalizedTerm);
            if (count > 0) {
                matched += 1.0;
                occurrence += Math.min(count, 3) / 3.0;
            }
        }
        double coverage = matched / terms.size();
        double occurrenceScore = occurrence / terms.size();
        return Math.min(1.0, coverage * 0.8 + occurrenceScore * 0.2);
    }

    private double phraseScore(String query, String content) {
        String compactQuery = compact(query);
        if (compactQuery.length() < 4 || content == null || content.isBlank()) {
            return 0.0;
        }
        return compact(content).contains(compactQuery) ? 1.0 : 0.0;
    }

    private double lengthScore(int length) {
        if (length <= 0) {
            return 0.3;
        }
        if (length < 80) {
            return 0.65;
        }
        if (length <= 2_500) {
            return 1.0;
        }
        if (length <= 5_000) {
            return 0.85;
        }
        return 0.7;
    }

    private double typeBoost(String query, Map<String, Object> metadata) {
        if (metadata == null) {
            return 0.0;
        }
        String blockType = String.valueOf(metadata.getOrDefault("block_type", ""));
        if (!"table".equalsIgnoreCase(blockType)) {
            return 0.0;
        }
        String compactQuery = compact(query);
        return containsAny(compactQuery, List.of(
            "\u8868",
            "\u8868\u683c",
            "\u5b57\u6bb5",
            "\u6570\u636e",
            "\u7edf\u8ba1",
            "\u591a\u5c11",
            "table",
            "column",
            "field",
            "data",
            "count",
            "excel",
            "csv"
        )) ? 0.05 : 0.0;
    }

    private String metadataText(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        List<String> preferredKeys = List.of(
            "original_filename",
            "source_type",
            "block_type",
            "section_path",
            "sheet_name",
            "chunk_strategy"
        );
        StringBuilder builder = new StringBuilder();
        for (String key : preferredKeys) {
            Object value = metadata.get(key);
            if (value != null) {
                builder.append(value).append(' ');
            }
        }
        return builder.toString();
    }

    private void attachRerankMetadata(List<Document> reranked, List<RankedDocument> rankedDocuments) {
        Map<Document, Double> scoreMap = new IdentityHashMap<>();
        for (RankedDocument rankedDocument : rankedDocuments) {
            scoreMap.put(rankedDocument.document(), rankedDocument.score());
        }
        for (int index = 0; index < reranked.size(); index++) {
            Document document = reranked.get(index);
            Double score = scoreMap.getOrDefault(document, 0.0);
            document.getMetadata().put("rerank_enabled", "true");
            document.getMetadata().put("rerank_rank", String.valueOf(index + 1));
            document.getMetadata().put("rerank_score", String.format(Locale.ROOT, "%.4f", score));
        }
    }

    private int normalizeLimit(int limit, int size) {
        if (limit <= 0) {
            return size;
        }
        return Math.min(limit, size);
    }

    private boolean containsAny(String text, List<String> candidates) {
        return candidates.stream().anyMatch(text::contains);
    }

    private int countOccurrences(String text, String term) {
        int count = 0;
        int fromIndex = 0;
        while (fromIndex < text.length()) {
            int index = text.indexOf(term, fromIndex);
            if (index < 0) {
                break;
            }
            count++;
            fromIndex = index + term.length();
        }
        return count;
    }

    private String normalizeForMatch(String text) {
        return compact(text).toLowerCase(Locale.ROOT);
    }

    private String compact(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch) || isHan(ch) || ch == '+' || ch == '#' || ch == '_') {
                builder.append(Character.toLowerCase(ch));
            }
        }
        return builder.toString();
    }

    private boolean isHan(char ch) {
        return Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
    }

    private record RankedDocument(Document document, double score, int originalIndex) {
    }
}