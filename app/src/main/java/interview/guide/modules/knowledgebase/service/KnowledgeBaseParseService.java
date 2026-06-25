package interview.guide.modules.knowledgebase.service;

import interview.guide.infrastructure.file.ContentTypeDetectionService;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.ParsedKnowledgeDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseParseService {

    private final DocumentParseService documentParseService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileStorageService storageService;

    public String parseContent(MultipartFile file) {
        log.info("Start parsing knowledge base content: {}", file.getOriginalFilename());
        return documentParseService.parseContent(file);
    }

    public ParsedKnowledgeDocument parseKnowledgeDocument(MultipartFile file) {
        log.info("Start parsing structured knowledge base document: {}", file.getOriginalFilename());
        return documentParseService.parseKnowledgeDocument(file);
    }

    public String parseContent(byte[] fileBytes, String fileName) {
        log.info("Start parsing knowledge base content from bytes: {}", fileName);
        return documentParseService.parseContent(fileBytes, fileName);
    }

    public ParsedKnowledgeDocument parseKnowledgeDocument(byte[] fileBytes, String fileName) {
        log.info("Start parsing structured knowledge base document from bytes: {}", fileName);
        return documentParseService.parseKnowledgeDocument(fileBytes, fileName);
    }

    public String downloadAndParseContent(String storageKey, String originalFilename) {
        log.info("Download and parse knowledge base content: {}", originalFilename);
        return documentParseService.downloadAndParseContent(storageService, storageKey, originalFilename);
    }

    public ParsedKnowledgeDocument downloadAndParseKnowledgeDocument(String storageKey, String originalFilename) {
        log.info("Download and parse structured knowledge base document: {}", originalFilename);
        return documentParseService.downloadAndParseKnowledgeDocument(storageService, storageKey, originalFilename);
    }

    public String detectContentType(MultipartFile file) {
        return contentTypeDetectionService.detectContentType(file);
    }
}
