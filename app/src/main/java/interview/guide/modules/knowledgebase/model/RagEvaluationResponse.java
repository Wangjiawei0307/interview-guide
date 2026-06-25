package interview.guide.modules.knowledgebase.model;

import java.util.List;
import java.util.Map;

public record RagEvaluationResponse(
    String question,
    String answer,
    double overallScore,
    RetrievalMetrics retrieval,
    GenerationMetrics generation,
    EngineeringMetrics engineering,
    List<RetrievedContext> retrievedContexts
) {

    public record RetrievalMetrics(
        MetricScore hitRateAtK,
        MetricScore mrr,
        MetricScore contextRecall,
        MetricScore contextPrecision
    ) {
    }

    public record GenerationMetrics(
        MetricScore faithfulness,
        MetricScore answerRelevancy,
        MetricScore citationAccuracy
    ) {
    }

    public record EngineeringMetrics(
        long totalLatencyMs,
        long retrievalLatencyMs,
        long rerankLatencyMs,
        long generationLatencyMs,
        int candidateCount,
        int contextCount,
        int topK,
        int estimatedInputTokens,
        int estimatedOutputTokens,
        int estimatedTotalTokens
    ) {
    }

    public record MetricScore(
        double score,
        String explanation
    ) {
    }

    public record RetrievedContext(
        int rank,
        String preview,
        Map<String, Object> metadata,
        boolean relevant,
        double maxEvidenceSimilarity,
        List<Integer> matchedEvidenceIndexes
    ) {
    }
}