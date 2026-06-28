package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.RagEvaluationRequest;
import interview.guide.modules.knowledgebase.model.RagEvaluationResponse;
import interview.guide.modules.knowledgebase.model.RagEvaluationResponse.EngineeringMetrics;
import interview.guide.modules.knowledgebase.model.RagEvaluationResponse.GenerationMetrics;
import interview.guide.modules.knowledgebase.model.RagEvaluationResponse.MetricScore;
import interview.guide.modules.knowledgebase.model.RagEvaluationResponse.RetrievalMetrics;
import interview.guide.modules.knowledgebase.model.RagEvaluationResponse.RetrievedContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class RagEvaluationService {

    private static final double EVIDENCE_MATCH_THRESHOLD = 0.42;
    private static final double CLAIM_SUPPORT_THRESHOLD = 0.35;
    private static final int PREVIEW_LENGTH = 280;
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]|\\u3010(\\d+)\\u3011");

    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRerankService rerankService;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseQueryProperties queryProperties;
    private final KnowledgeBaseAccessService accessService;

    public RagEvaluationService(
        KnowledgeBaseVectorService vectorService,
        KnowledgeBaseRerankService rerankService,
        KnowledgeBaseQueryService queryService,
        KnowledgeBaseQueryProperties queryProperties,
        KnowledgeBaseAccessService accessService
    ) {
        this.vectorService = vectorService;
        this.rerankService = rerankService;
        this.queryService = queryService;
        this.queryProperties = queryProperties;
        this.accessService = accessService;
    }

    public RagEvaluationResponse evaluate(RagEvaluationRequest request) {
        long totalStart = System.nanoTime();
        int topK = resolveTopK(request.topK());
        int candidateTopK = Math.max(queryProperties.getSearch().getTopkMedium(), topK * 3);
        double minScore = queryProperties.getSearch().getMinScoreDefault();
        List<Long> readableIds = accessService.filterReadableIds(request.knowledgeBaseIds());

        long retrievalStart = System.nanoTime();
        List<Document> candidates = vectorService.similaritySearch(
            request.question(),
            readableIds,
            candidateTopK,
            minScore
        );
        long retrievalLatencyMs = elapsedMs(retrievalStart);

        long rerankStart = System.nanoTime();
        List<Document> contexts = rerankService.rerank(request.question(), candidates, topK);
        long rerankLatencyMs = elapsedMs(rerankStart);

        long generationLatencyMs = 0;
        String answer = normalize(request.answer());
        if (answer.isBlank() && Boolean.TRUE.equals(request.generateAnswer())) {
            long generationStart = System.nanoTime();
            answer = queryService.answerQuestion(readableIds, request.question());
            generationLatencyMs = elapsedMs(generationStart);
        }

        List<String> expectedEvidence = cleanList(request.expectedEvidence());
        String contextText = joinDocumentText(contexts);
        RetrievalResult retrievalResult = evaluateRetrieval(contexts, expectedEvidence, topK);
        GenerationMetrics generationMetrics = evaluateGeneration(
            request.question(),
            answer,
            request.expectedAnswer(),
            contextText,
            contexts,
            expectedEvidence
        );
        EngineeringMetrics engineeringMetrics = buildEngineeringMetrics(
            request.question(),
            answer,
            contextText,
            totalStart,
            retrievalLatencyMs,
            rerankLatencyMs,
            generationLatencyMs,
            candidates.size(),
            contexts.size(),
            topK
        );

        double overallScore = averageScores(
            retrievalResult.metrics().hitRateAtK(),
            retrievalResult.metrics().mrr(),
            retrievalResult.metrics().contextRecall(),
            retrievalResult.metrics().contextPrecision(),
            generationMetrics.faithfulness(),
            generationMetrics.answerRelevancy(),
            generationMetrics.citationAccuracy()
        );

        log.info("RAG evaluation completed: kbIds={}, topK={}, overallScore={}",
            readableIds, topK, overallScore);
        return new RagEvaluationResponse(
            request.question(),
            answer,
            round4(overallScore),
            retrievalResult.metrics(),
            generationMetrics,
            engineeringMetrics,
            retrievalResult.contexts()
        );
    }

    private RetrievalResult evaluateRetrieval(List<Document> contexts, List<String> expectedEvidence, int topK) {
        List<RetrievedContext> retrievedContexts = new ArrayList<>();
        boolean anyHit = false;
        int firstHitRank = 0;
        int relevantContextCount = 0;
        boolean[] evidenceMatched = new boolean[expectedEvidence.size()];

        for (int i = 0; i < contexts.size(); i++) {
            Document document = contexts.get(i);
            EvidenceMatch match = matchEvidence(document.getText(), expectedEvidence);
            if (match.relevant()) {
                relevantContextCount++;
                anyHit = true;
                if (firstHitRank == 0) {
                    firstHitRank = i + 1;
                }
                for (Integer evidenceIndex : match.matchedEvidenceIndexes()) {
                    evidenceMatched[evidenceIndex] = true;
                }
            }
            retrievedContexts.add(new RetrievedContext(
                i + 1,
                preview(document.getText()),
                safeMetadata(document),
                match.relevant(),
                round4(match.maxSimilarity()),
                match.matchedEvidenceIndexes().stream().map(index -> index + 1).toList()
            ));
        }

        int matchedEvidenceCount = 0;
        for (boolean matched : evidenceMatched) {
            if (matched) {
                matchedEvidenceCount++;
            }
        }

        double hitRate = anyHit ? 1.0 : 0.0;
        double mrr = firstHitRank == 0 ? 0.0 : 1.0 / firstHitRank;
        double contextRecall = expectedEvidence.isEmpty()
            ? 0.0
            : (double) matchedEvidenceCount / expectedEvidence.size();
        double contextPrecision = contexts.isEmpty() ? 0.0 : (double) relevantContextCount / contexts.size();

        RetrievalMetrics metrics = new RetrievalMetrics(
            metric(hitRate, "Hit evidence in top " + topK + ": " + anyHit),
            metric(mrr, firstHitRank == 0
                ? "No relevant evidence found"
                : "First relevant evidence rank=" + firstHitRank),
            metric(contextRecall, "Expected evidence matched=" + matchedEvidenceCount + "/" + expectedEvidence.size()),
            metric(contextPrecision, "Relevant retrieved contexts=" + relevantContextCount + "/" + contexts.size())
        );
        return new RetrievalResult(metrics, retrievedContexts);
    }

    private GenerationMetrics evaluateGeneration(
        String question,
        String answer,
        String expectedAnswer,
        String contextText,
        List<Document> contexts,
        List<String> expectedEvidence
    ) {
        MetricScore faithfulness = evaluateFaithfulness(answer, contextText);
        MetricScore answerRelevancy = evaluateAnswerRelevancy(question, answer, expectedAnswer);
        MetricScore citationAccuracy = evaluateCitationAccuracy(answer, contexts, expectedEvidence);
        return new GenerationMetrics(faithfulness, answerRelevancy, citationAccuracy);
    }

    private MetricScore evaluateFaithfulness(String answer, String contextText) {
        if (answer == null || answer.isBlank()) {
            return metric(0.0, "No answer to evaluate");
        }
        List<String> claims = splitClaims(answer);
        if (claims.isEmpty()) {
            return metric(0.0, "No evaluable claims extracted from answer");
        }
        int supported = 0;
        for (String claim : claims) {
            if (termCoverage(extractTerms(claim), contextText) >= CLAIM_SUPPORT_THRESHOLD) {
                supported++;
            }
        }
        double score = (double) supported / claims.size();
        return metric(score, "Supported claims=" + supported + "/" + claims.size());
    }

    private MetricScore evaluateAnswerRelevancy(String question, String answer, String expectedAnswer) {
        if (answer == null || answer.isBlank()) {
            return metric(0.0, "No answer to evaluate");
        }
        double questionCoverage = termCoverage(extractTerms(question), answer);
        String expected = normalize(expectedAnswer);
        if (expected.isBlank()) {
            return metric(questionCoverage, "Question term coverage=" + round4(questionCoverage));
        }
        double expectedSimilarity = similarity(answer, expected);
        double score = questionCoverage * 0.6 + expectedSimilarity * 0.4;
        return metric(score, "Question coverage=" + round4(questionCoverage)
            + ", expected answer similarity=" + round4(expectedSimilarity));
    }

    private MetricScore evaluateCitationAccuracy(
        String answer,
        List<Document> contexts,
        List<String> expectedEvidence
    ) {
        List<Integer> citations = parseCitations(answer);
        if (citations.isEmpty()) {
            return metric(0.0, "No citation marker such as [1] or U+30101U+3011");
        }

        int valid = 0;
        for (Integer citation : citations) {
            if (citation <= 0 || citation > contexts.size()) {
                continue;
            }
            Document citedContext = contexts.get(citation - 1);
            EvidenceMatch match = matchEvidence(citedContext.getText(), expectedEvidence);
            if (match.relevant() || termCoverage(extractTerms(answer), citedContext.getText()) >= 0.2) {
                valid++;
            }
        }
        double score = (double) valid / citations.size();
        return metric(score, "Valid citations=" + valid + "/" + citations.size());
    }

    private EvidenceMatch matchEvidence(String text, List<String> expectedEvidence) {
        List<Integer> matchedIndexes = new ArrayList<>();
        double maxSimilarity = 0.0;
        for (int i = 0; i < expectedEvidence.size(); i++) {
            String evidence = expectedEvidence.get(i);
            double score = similarity(text, evidence);
            maxSimilarity = Math.max(maxSimilarity, score);
            if (score >= EVIDENCE_MATCH_THRESHOLD || containsCompact(text, evidence)) {
                matchedIndexes.add(i);
            }
        }
        return new EvidenceMatch(!matchedIndexes.isEmpty(), maxSimilarity, matchedIndexes);
    }

    private double similarity(String left, String right) {
        Set<String> leftTerms = new LinkedHashSet<>(extractTerms(left));
        Set<String> rightTerms = new LinkedHashSet<>(extractTerms(right));
        if (leftTerms.isEmpty() || rightTerms.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        for (String term : rightTerms) {
            if (leftTerms.contains(term)) {
                intersection++;
            }
        }
        double recall = (double) intersection / rightTerms.size();
        double precision = (double) intersection / leftTerms.size();
        if (precision + recall == 0) {
            return 0.0;
        }
        return 2 * precision * recall / (precision + recall);
    }

    private double termCoverage(List<String> terms, String text) {
        if (terms.isEmpty() || text == null || text.isBlank()) {
            return 0.0;
        }
        String compactText = compact(text);
        int matched = 0;
        for (String term : terms) {
            String compactTerm = compact(term);
            if (!compactTerm.isBlank() && compactText.contains(compactTerm)) {
                matched++;
            }
        }
        return (double) matched / terms.size();
    }

    private List<String> extractTerms(String text) {
        String compact = compact(text);
        if (compact.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder han = new StringBuilder();
        for (int i = 0; i < compact.length(); i++) {
            char ch = compact.charAt(i);
            if (isHan(ch)) {
                flushAscii(terms, ascii);
                han.append(ch);
            } else {
                flushHan(terms, han);
                ascii.append(ch);
            }
        }
        flushAscii(terms, ascii);
        flushHan(terms, han);
        if (compact.length() >= 2 && compact.length() <= 32) {
            terms.add(compact);
        }
        return new ArrayList<>(terms);
    }

    private void flushAscii(Set<String> terms, StringBuilder token) {
        if (token.length() >= 2) {
            terms.add(token.toString());
        }
        token.setLength(0);
    }

    private void flushHan(Set<String> terms, StringBuilder token) {
        String value = token.toString();
        token.setLength(0);
        if (value.isBlank()) {
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

    private List<String> splitClaims(String answer) {
        return Arrays.stream(answer.split("[\\u3002\\uff01\\uff1f!?\\n\\uff1b;]+"))
            .map(String::trim)
            .filter(claim -> claim.length() >= 8)
            .toList();
    }

    private List<Integer> parseCitations(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        List<Integer> citations = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            citations.add(Integer.parseInt(value));
        }
        return citations;
    }

    private EngineeringMetrics buildEngineeringMetrics(
        String question,
        String answer,
        String contextText,
        long totalStart,
        long retrievalLatencyMs,
        long rerankLatencyMs,
        long generationLatencyMs,
        int candidateCount,
        int contextCount,
        int topK
    ) {
        int inputTokens = estimateTokens(question + "\n" + contextText);
        int outputTokens = estimateTokens(answer);
        return new EngineeringMetrics(
            elapsedMs(totalStart),
            retrievalLatencyMs,
            rerankLatencyMs,
            generationLatencyMs,
            candidateCount,
            contextCount,
            topK,
            inputTokens,
            outputTokens,
            inputTokens + outputTokens
        );
    }

    private String joinDocumentText(List<Document> documents) {
        return documents.stream()
            .map(Document::getText)
            .reduce((left, right) -> left + "\n\n---\n\n" + right)
            .orElse("");
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(this::normalize)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private Map<String, Object> safeMetadata(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return metadata == null ? Map.of() : metadata;
    }

    private boolean containsCompact(String text, String expected) {
        String compactText = compact(text);
        String compactExpected = compact(expected);
        return compactExpected.length() >= 8 && compactText.contains(compactExpected);
    }

    private String preview(String text) {
        String normalized = normalize(text).replaceAll("\\s+", " ");
        if (normalized.length() <= PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_LENGTH) + "...";
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private String compact(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch) || isHan(ch) || ch == '+' || ch == '#' || ch == '_') {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private boolean isHan(char ch) {
        return Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
    }

    private int resolveTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return queryProperties.getRerank().getFinalTopkMedium();
        }
        return Math.min(topK, 20);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int compactLength = compact(text).length();
        return Math.max(1, (int) Math.ceil(compactLength * 0.65));
    }

    private MetricScore metric(double score, String explanation) {
        return new MetricScore(round4(clamp(score)), explanation);
    }

    private double averageScores(MetricScore... scores) {
        double total = 0.0;
        for (MetricScore score : scores) {
            total += score.score();
        }
        return scores.length == 0 ? 0.0 : total / scores.length;
    }

    private double clamp(double score) {
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record EvidenceMatch(boolean relevant, double maxSimilarity, List<Integer> matchedEvidenceIndexes) {
    }

    private record RetrievalResult(RetrievalMetrics metrics, List<RetrievedContext> contexts) {
    }
}