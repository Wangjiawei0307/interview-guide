package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.infrastructure.file.ParsedKnowledgeDocument;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    private final KnowledgeBaseParseService parseService;
    private final KnowledgeBasePersistenceService persistenceService;
    private final FileStorageService storageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final VectorizeStreamProducer vectorizeStreamProducer;
    private final KnowledgeDocumentPayloadCodec documentPayloadCodec;

    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category) {
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");

        String fileName = file.getOriginalFilename();
        log.info("Received knowledge base upload: fileName={}, size={}, category={}",
            fileName, file.getSize(), category);

        String contentType = parseService.detectContentType(file);
        validateContentType(contentType, fileName);

        String fileHash = fileHashService.calculateHash(file);
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            log.info("Duplicate knowledge base detected: hash={}", fileHash);
            return persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
        }

        ParsedKnowledgeDocument document = parseService.parseKnowledgeDocument(file);
        if (!document.hasIndexableContent()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取可索引内容，请确认文件格式正确");
        }
        String vectorPayload = documentPayloadCodec.encode(document);

        String fileKey = storageService.uploadKnowledgeBase(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("Knowledge base file stored: storageKey={}", fileKey);

        KnowledgeBaseEntity savedKb = persistenceService.saveKnowledgeBase(
            file, name, category, fileKey, fileUrl, fileHash);

        vectorizeStreamProducer.sendVectorizeTask(savedKb.getId(), vectorPayload);

        log.info("Knowledge base upload completed, vector task queued: fileName={}, kbId={}, blocks={}",
            fileName, savedKb.getId(), document.blocks().size());

        return Map.of(
            "knowledgeBase", Map.of(
                "id", savedKb.getId(),
                "name", savedKb.getName(),
                "category", savedKb.getCategory() != null ? savedKb.getCategory() : "",
                "fileSize", savedKb.getFileSize(),
                "contentLength", document.indexableLength(),
                "blockCount", document.blocks().size(),
                "vectorStatus", VectorStatus.PENDING.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl
            ),
            "duplicate", false
        );
    }

    private void validateContentType(String contentType, String fileName) {
        fileValidationService.validateContentType(
            contentType,
            fileName,
            fileValidationService::isKnowledgeBaseMimeType,
            fileValidationService::isKnowledgeBaseExtension,
            "不支持的文件类型: " + contentType + "，支持 PDF、Word、Excel、CSV、TXT、Markdown、RTF 和常见图片格式"
        );
    }

    public void revectorize(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        log.info("Start revectorizing knowledge base: kbId={}, name={}", kbId, kb.getName());

        ParsedKnowledgeDocument document = parseService.downloadAndParseKnowledgeDocument(
            kb.getStorageKey(), kb.getOriginalFilename());
        if (!document.hasIndexableContent()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取可索引内容");
        }
        String vectorPayload = documentPayloadCodec.encode(document);

        persistenceService.updateVectorStatusToPending(kbId);
        vectorizeStreamProducer.sendVectorizeTask(kbId, vectorPayload);

        log.info("Revectorize task queued: kbId={}, blocks={}", kbId, document.blocks().size());
    }
}
