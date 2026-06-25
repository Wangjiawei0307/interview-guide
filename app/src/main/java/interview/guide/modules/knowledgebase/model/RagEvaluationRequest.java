package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record RagEvaluationRequest(
    @NotEmpty(message = "至少选择一个知识库")
    List<Long> knowledgeBaseIds,

    @NotBlank(message = "评测问题不能为空")
    String question,

    @NotEmpty(message = "至少提供一条标准证据")
    List<String> expectedEvidence,

    String expectedAnswer,

    String answer,

    @Positive(message = "topK 必须大于 0")
    Integer topK,

    Boolean generateAnswer
) {
}