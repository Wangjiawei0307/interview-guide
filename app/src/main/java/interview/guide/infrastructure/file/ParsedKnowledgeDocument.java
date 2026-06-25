package interview.guide.infrastructure.file;

import java.util.List;

public record ParsedKnowledgeDocument(
    String sourceType,
    String originalFilename,
    List<ParsedDocumentBlock> blocks
) {

    public ParsedKnowledgeDocument {
        sourceType = sourceType == null || sourceType.isBlank() ? "unknown" : sourceType;
        originalFilename = originalFilename == null ? "" : originalFilename;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public boolean hasIndexableContent() {
        return blocks.stream()
            .map(ParsedDocumentBlock::content)
            .anyMatch(content -> content != null && !content.isBlank());
    }

    public int indexableLength() {
        return toIndexText().length();
    }

    public String toIndexText() {
        return blocks.stream()
            .map(ParsedDocumentBlock::content)
            .filter(content -> content != null && !content.isBlank())
            .reduce((left, right) -> left + "\n\n" + right)
            .orElse("");
    }
}
