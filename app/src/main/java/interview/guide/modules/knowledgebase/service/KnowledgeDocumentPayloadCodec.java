package interview.guide.modules.knowledgebase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.ParsedDocumentBlock;
import interview.guide.infrastructure.file.ParsedKnowledgeDocument;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class KnowledgeDocumentPayloadCodec {

    private static final String PAYLOAD_PREFIX = "__RAG_DOC_V2__";

    private final ObjectMapper objectMapper;

    public KnowledgeDocumentPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(ParsedKnowledgeDocument document) {
        try {
            return PAYLOAD_PREFIX + objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "结构化文档序列化失败: " + e.getMessage());
        }
    }

    public ParsedKnowledgeDocument decodeOrLegacy(String payload) {
        if (payload == null || payload.isBlank()) {
            return new ParsedKnowledgeDocument("legacy-text", "", List.of());
        }
        if (!payload.startsWith(PAYLOAD_PREFIX)) {
            return new ParsedKnowledgeDocument(
                "legacy-text",
                "",
                List.of(ParsedDocumentBlock.text(payload, Map.of("source_type", "legacy-text")))
            );
        }
        try {
            String json = payload.substring(PAYLOAD_PREFIX.length());
            return objectMapper.readValue(json, ParsedKnowledgeDocument.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "结构化文档反序列化失败: " + e.getMessage());
        }
    }
}
