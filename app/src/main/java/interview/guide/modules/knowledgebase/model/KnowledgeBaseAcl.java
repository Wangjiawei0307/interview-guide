package interview.guide.modules.knowledgebase.model;

import java.util.Locale;

public enum KnowledgeBaseAcl {
    PUBLIC,
    PRIVATE,
    SHARED;

    public static KnowledgeBaseAcl from(String value) {
        if (value == null || value.isBlank()) {
            return PUBLIC;
        }
        try {
            return KnowledgeBaseAcl.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PUBLIC;
        }
    }
}