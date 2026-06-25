package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

@Slf4j
@Service
public class DocumentParseService {

    private static final int MAX_TEXT_LENGTH = 5 * 1024 * 1024;
    private static final int TABLE_ROWS_PER_BLOCK = 50;

    private final TextCleaningService textCleaningService;

    public DocumentParseService(TextCleaningService textCleaningService) {
        this.textCleaningService = textCleaningService;
    }

    public String parseContent(MultipartFile file) {
        return parseKnowledgeDocument(file).toIndexText();
    }

    public String parseContent(byte[] fileBytes, String fileName) {
        return parseKnowledgeDocument(fileBytes, fileName).toIndexText();
    }

    public ParsedKnowledgeDocument parseKnowledgeDocument(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("Start parsing document: {}", fileName);

        if (file.isEmpty() || file.getSize() == 0) {
            log.warn("Document is empty: {}", fileName);
            return new ParsedKnowledgeDocument(detectSourceType(fileName), safeFilename(fileName), List.of());
        }

        try {
            return parseKnowledgeDocument(file.getBytes(), fileName);
        } catch (IOException e) {
            log.error("Read uploaded document failed: fileName={}", fileName, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取文件失败: " + e.getMessage());
        }
    }

    public ParsedKnowledgeDocument parseKnowledgeDocument(byte[] fileBytes, String fileName) {
        String sourceType = detectSourceType(fileName);
        log.info("Start parsing document bytes: fileName={}, sourceType={}", fileName, sourceType);

        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("Document bytes are empty: {}", fileName);
            return new ParsedKnowledgeDocument(sourceType, safeFilename(fileName), List.of());
        }

        try {
            return switch (sourceType) {
                case "excel" -> parseWorkbookDocument(fileBytes, fileName);
                case "csv" -> parseCsvDocument(fileBytes, fileName);
                case "word" -> isDocx(fileName)
                    ? parseDocxDocument(fileBytes, fileName)
                    : parseTikaDocument(fileBytes, fileName, sourceType);
                case "image" -> parseImageDocument(fileBytes, fileName);
                default -> parseTikaDocument(fileBytes, fileName, sourceType);
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Document parse failed: fileName={}, sourceType={}", fileName, sourceType, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败: " + e.getMessage());
        }
    }

    public ParsedKnowledgeDocument downloadAndParseKnowledgeDocument(
        FileStorageService storageService,
        String storageKey,
        String originalFilename
    ) {
        try {
            byte[] fileBytes = storageService.downloadFile(storageKey);
            if (fileBytes == null || fileBytes.length == 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载文件失败");
            }
            return parseKnowledgeDocument(fileBytes, originalFilename);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Download and parse document failed: storageKey={}", storageKey, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载并解析文件失败: " + e.getMessage());
        }
    }

    public String downloadAndParseContent(
        FileStorageService storageService,
        String storageKey,
        String originalFilename
    ) {
        return downloadAndParseKnowledgeDocument(storageService, storageKey, originalFilename).toIndexText();
    }

    private ParsedKnowledgeDocument parseTikaDocument(
        byte[] fileBytes,
        String fileName,
        String sourceType
    ) throws IOException, TikaException, SAXException {
        String content = parseContent(new ByteArrayInputStream(fileBytes));
        String cleanedContent = textCleaningService.cleanText(content);
        Map<String, String> metadata = baseMetadata(sourceType, fileName);
        metadata.put("parser", "tika");
        if ("pdf".equals(sourceType)) {
            metadata.put("layout_strategy", "sort_by_position");
        }
        log.info("Document parsed by Tika: fileName={}, length={}", fileName, cleanedContent.length());
        return new ParsedKnowledgeDocument(
            sourceType,
            safeFilename(fileName),
            List.of(ParsedDocumentBlock.text(cleanedContent, metadata))
        );
    }

    private ParsedKnowledgeDocument parseWorkbookDocument(byte[] fileBytes, String fileName) throws IOException {
        List<ParsedDocumentBlock> blocks = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(fileBytes))) {
            int tableIndex = 0;
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                List<List<String>> rows = readSheetRows(sheet, formatter);
                if (rows.isEmpty()) {
                    continue;
                }

                List<String> header = rows.get(0);
                List<List<String>> dataRows = rows.size() > 1 ? rows.subList(1, rows.size()) : List.of();
                if (dataRows.isEmpty()) {
                    blocks.add(ParsedDocumentBlock.table(
                        buildTableContent("Sheet: " + sheet.getSheetName(), List.of(header)),
                        tableMetadata("excel", fileName, sheet.getSheetName(), sheetIndex, 0, 0, tableIndex++)
                    ));
                    continue;
                }

                for (int start = 0; start < dataRows.size(); start += TABLE_ROWS_PER_BLOCK) {
                    int end = Math.min(start + TABLE_ROWS_PER_BLOCK, dataRows.size());
                    List<List<String>> chunkRows = new ArrayList<>();
                    chunkRows.add(header);
                    chunkRows.addAll(dataRows.subList(start, end));

                    String title = "Sheet: " + sheet.getSheetName() + ", rows " + (start + 1) + "-" + end;
                    blocks.add(ParsedDocumentBlock.table(
                        buildTableContent(title, chunkRows),
                        tableMetadata("excel", fileName, sheet.getSheetName(), sheetIndex, start + 1, end, tableIndex++)
                    ));
                }
            }
        }

        log.info("Workbook parsed: fileName={}, tableBlocks={}", fileName, blocks.size());
        return new ParsedKnowledgeDocument("excel", safeFilename(fileName), blocks);
    }

    private ParsedKnowledgeDocument parseCsvDocument(byte[] fileBytes, String fileName) {
        String raw = new String(fileBytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
        List<List<String>> rows = raw.lines()
            .map(this::parseCsvLine)
            .filter(row -> row.stream().anyMatch(value -> !value.isBlank()))
            .toList();

        List<ParsedDocumentBlock> blocks = new ArrayList<>();
        if (!rows.isEmpty()) {
            List<String> header = rows.get(0);
            List<List<String>> dataRows = rows.size() > 1 ? rows.subList(1, rows.size()) : List.of();
            int tableIndex = 0;
            for (int start = 0; start < Math.max(dataRows.size(), 1); start += TABLE_ROWS_PER_BLOCK) {
                int end = Math.min(start + TABLE_ROWS_PER_BLOCK, dataRows.size());
                List<List<String>> chunkRows = new ArrayList<>();
                chunkRows.add(header);
                if (!dataRows.isEmpty()) {
                    chunkRows.addAll(dataRows.subList(start, end));
                }
                blocks.add(ParsedDocumentBlock.table(
                    buildTableContent("CSV: " + safeFilename(fileName), chunkRows),
                    tableMetadata("csv", fileName, "csv", 0, start + 1, end, tableIndex++)
                ));
                if (dataRows.isEmpty()) {
                    break;
                }
            }
        }
        return new ParsedKnowledgeDocument("csv", safeFilename(fileName), blocks);
    }

    private ParsedKnowledgeDocument parseDocxDocument(byte[] fileBytes, String fileName) throws IOException, TikaException, SAXException {
        List<ParsedDocumentBlock> blocks = new ArrayList<>();
        List<String> sectionPath = new ArrayList<>();
        StringBuilder textBuffer = new StringBuilder();
        int tableIndex = 0;

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes))) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH && element instanceof XWPFParagraph paragraph) {
                    String text = safeText(paragraph.getText());
                    if (text.isBlank()) {
                        continue;
                    }
                    Integer headingLevel = detectHeadingLevel(paragraph);
                    if (headingLevel != null) {
                        flushTextBlock(blocks, textBuffer, fileName, sectionPath);
                        updateSectionPath(sectionPath, headingLevel, text);
                        textBuffer.append("#".repeat(Math.min(headingLevel, 6))).append(' ').append(text).append("\n\n");
                    } else {
                        textBuffer.append(text).append("\n\n");
                    }
                    continue;
                }

                if (element.getElementType() == BodyElementType.TABLE && element instanceof XWPFTable table) {
                    flushTextBlock(blocks, textBuffer, fileName, sectionPath);
                    Map<String, String> metadata = baseMetadata("word", fileName);
                    metadata.put("block_type", "table");
                    metadata.put("chunk_strategy", "table_standalone");
                    metadata.put("section_path", String.join(" > ", sectionPath));
                    metadata.put("table_index", String.valueOf(tableIndex));
                    blocks.add(ParsedDocumentBlock.table(
                        buildWordTableContent(sectionPath, table),
                        metadata
                    ));
                    tableIndex++;
                }
            }
        }
        flushTextBlock(blocks, textBuffer, fileName, sectionPath);

        if (blocks.isEmpty()) {
            return parseTikaDocument(fileBytes, fileName, "word");
        }
        log.info("DOCX parsed: fileName={}, blocks={}", fileName, blocks.size());
        return new ParsedKnowledgeDocument("word", safeFilename(fileName), blocks);
    }

    private ParsedKnowledgeDocument parseImageDocument(byte[] fileBytes, String fileName) throws IOException {
        Map<String, String> metadata = baseMetadata("image", fileName);
        metadata.put("block_type", "image");
        metadata.put("needs_ocr", "true");
        metadata.put("chunk_strategy", "image_metadata_placeholder");

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(fileBytes));
        StringBuilder content = new StringBuilder("图片文件：").append(safeFilename(fileName));
        if (image != null) {
            metadata.put("image_width", String.valueOf(image.getWidth()));
            metadata.put("image_height", String.valueOf(image.getHeight()));
            content.append("，宽度：").append(image.getWidth())
                .append("，高度：").append(image.getHeight());
        }
        content.append("。当前版本保留图片基础元数据作为可检索内容，后续可接入 OCR 或视觉模型生成 caption 后再入库。");

        return new ParsedKnowledgeDocument(
            "image",
            safeFilename(fileName),
            List.of(ParsedDocumentBlock.image(content.toString(), metadata))
        );
    }

    private String parseContent(InputStream inputStream) throws IOException, TikaException, SAXException {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        context.set(Parser.class, parser);
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());

        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setSortByPosition(true);
        context.set(PDFParserConfig.class, pdfConfig);

        parser.parse(inputStream, handler, metadata, context);
        return handler.toString();
    }

    private List<List<String>> readSheetRows(Sheet sheet, DataFormatter formatter) {
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            short lastCellNum = row.getLastCellNum();
            if (lastCellNum < 0) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (int cellIndex = 0; cellIndex < lastCellNum; cellIndex++) {
                Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                values.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
            }
            trimTrailingBlankCells(values);
            if (values.stream().anyMatch(value -> !value.isBlank())) {
                rows.add(values);
            }
        }
        return rows;
    }

    private String buildTableContent(String title, List<List<String>> rows) {
        if (rows.isEmpty()) {
            return title;
        }
        List<String> header = normalizeHeader(rows.get(0));
        StringBuilder markdown = new StringBuilder("# ").append(title).append("\n\n");
        appendMarkdownRow(markdown, header);
        appendMarkdownSeparator(markdown, header.size());
        for (int i = 1; i < rows.size(); i++) {
            appendMarkdownRow(markdown, padRow(rows.get(i), header.size()));
        }
        markdown.append("\nRows as JSONL:\n");
        for (int i = 1; i < rows.size(); i++) {
            markdown.append(rowToJsonLine(header, rows.get(i))).append('\n');
        }
        return markdown.toString().trim();
    }

    private String buildWordTableContent(List<String> sectionPath, XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow tableRow : table.getRows()) {
            List<String> values = new ArrayList<>();
            for (XWPFTableCell cell : tableRow.getTableCells()) {
                values.add(safeText(cell.getText()).replace('\n', ' '));
            }
            trimTrailingBlankCells(values);
            if (values.stream().anyMatch(value -> !value.isBlank())) {
                rows.add(values);
            }
        }
        String title = sectionPath.isEmpty() ? "Word Table" : "Word Table: " + String.join(" > ", sectionPath);
        return buildTableContent(title, rows);
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString().trim());
        trimTrailingBlankCells(values);
        return values;
    }

    private void flushTextBlock(
        List<ParsedDocumentBlock> blocks,
        StringBuilder textBuffer,
        String fileName,
        List<String> sectionPath
    ) {
        String cleaned = textCleaningService.cleanText(textBuffer.toString());
        textBuffer.setLength(0);
        if (cleaned.isBlank()) {
            return;
        }
        Map<String, String> metadata = baseMetadata("word", fileName);
        metadata.put("block_type", "text");
        metadata.put("section_path", String.join(" > ", sectionPath));
        blocks.add(ParsedDocumentBlock.text(cleaned, metadata));
    }

    private Map<String, String> tableMetadata(
        String sourceType,
        String fileName,
        String sheetName,
        int sheetIndex,
        int rowStart,
        int rowEnd,
        int tableIndex
    ) {
        Map<String, String> metadata = baseMetadata(sourceType, fileName);
        metadata.put("block_type", "table");
        metadata.put("chunk_strategy", "table_standalone");
        metadata.put("sheet_name", sheetName);
        metadata.put("sheet_index", String.valueOf(sheetIndex));
        metadata.put("row_start", String.valueOf(rowStart));
        metadata.put("row_end", String.valueOf(rowEnd));
        metadata.put("table_index", String.valueOf(tableIndex));
        return metadata;
    }

    private Map<String, String> baseMetadata(String sourceType, String fileName) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source_type", sourceType);
        metadata.put("original_filename", safeFilename(fileName));
        return metadata;
    }

    private Integer detectHeadingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null || style.isBlank()) {
            return null;
        }
        String normalized = style.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
        if (!normalized.startsWith("heading")) {
            return null;
        }
        String digits = normalized.substring("heading".length()).replaceAll("\\D", "");
        if (digits.isBlank()) {
            return 1;
        }
        return Math.max(1, Math.min(6, Integer.parseInt(digits.substring(0, 1))));
    }

    private void updateSectionPath(List<String> sectionPath, int level, String title) {
        while (sectionPath.size() >= level) {
            sectionPath.remove(sectionPath.size() - 1);
        }
        sectionPath.add(title);
    }

    private List<String> normalizeHeader(List<String> row) {
        List<String> header = new ArrayList<>();
        for (int i = 0; i < Math.max(row.size(), 1); i++) {
            String value = i < row.size() ? row.get(i) : "";
            header.add(value == null || value.isBlank() ? "Column " + (i + 1) : value.trim());
        }
        return header;
    }

    private List<String> padRow(List<String> row, int size) {
        List<String> padded = new ArrayList<>(row);
        while (padded.size() < size) {
            padded.add("");
        }
        if (padded.size() > size) {
            return padded.subList(0, size);
        }
        return padded;
    }

    private String rowToJsonLine(List<String> header, List<String> row) {
        List<String> padded = padRow(row, header.size());
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < header.size(); i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append('"').append(escapeJson(header.get(i))).append("\":");
            json.append('"').append(escapeJson(padded.get(i))).append('"');
        }
        return json.append('}').toString();
    }

    private void appendMarkdownRow(StringBuilder markdown, List<String> row) {
        markdown.append('|');
        for (String value : row) {
            markdown.append(' ').append(escapeMarkdown(value)).append(" |");
        }
        markdown.append('\n');
    }

    private void appendMarkdownSeparator(StringBuilder markdown, int size) {
        markdown.append('|');
        for (int i = 0; i < size; i++) {
            markdown.append(" --- |");
        }
        markdown.append('\n');
    }

    private void trimTrailingBlankCells(List<String> values) {
        for (int i = values.size() - 1; i >= 0; i--) {
            if (!values.get(i).isBlank()) {
                return;
            }
            values.remove(i);
        }
    }

    private String detectSourceType(String fileName) {
        String lower = safeFilename(fileName).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return "excel";
        }
        if (lower.endsWith(".csv")) {
            return "csv";
        }
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            return "word";
        }
        if (lower.endsWith(".pdf")) {
            return "pdf";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mdown")) {
            return "markdown";
        }
        if (isImageExtension(lower)) {
            return "image";
        }
        return "text";
    }

    private boolean isDocx(String fileName) {
        return safeFilename(fileName).toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    private boolean isImageExtension(String lowerFileName) {
        return lowerFileName.endsWith(".png")
            || lowerFileName.endsWith(".jpg")
            || lowerFileName.endsWith(".jpeg")
            || lowerFileName.endsWith(".webp")
            || lowerFileName.endsWith(".gif")
            || lowerFileName.endsWith(".bmp")
            || lowerFileName.endsWith(".tif")
            || lowerFileName.endsWith(".tiff");
    }

    private String safeFilename(String fileName) {
        return fileName == null ? "" : fileName;
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private String escapeMarkdown(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ").trim();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").trim();
    }
}
