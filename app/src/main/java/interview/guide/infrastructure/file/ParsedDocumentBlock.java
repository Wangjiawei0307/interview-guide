package interview.guide.infrastructure.file;

import java.util.LinkedHashMap;
import java.util.Map;

public record ParsedDocumentBlock(
    BlockType type,
    String content,
    Map<String, String> metadata
) {

    public ParsedDocumentBlock {
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ParsedDocumentBlock text(String content, Map<String, String> metadata) {
        return new ParsedDocumentBlock(BlockType.TEXT, content, metadata);
    }

    public static ParsedDocumentBlock table(String content, Map<String, String> metadata) {
        return new ParsedDocumentBlock(BlockType.TABLE, content, metadata);
    }

    public static ParsedDocumentBlock image(String content, Map<String, String> metadata) {
        return new ParsedDocumentBlock(BlockType.IMAGE, content, metadata);
    }

    public Map<String, String> mutableMetadata() {
        return new LinkedHashMap<>(metadata);
    }

    public enum BlockType {
        TEXT,
        TABLE,
        IMAGE
    }
}
