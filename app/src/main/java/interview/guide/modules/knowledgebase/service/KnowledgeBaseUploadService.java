package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserContext;
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

import java.util.LinkedHashMap;
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
    private final KnowledgeBaseAccessService accessService;

    public Map<String, Object> uploadKnowledgeBase(
        MultipartFile file,
        String name,
        String category,
        String acl,
        String aclUsers,
        String aclRoles
    ) {
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");
        CurrentUserContext currentUser = accessService.currentUser();

        String fileName = file.getOriginalFilename();
        log.info("Received knowledge base upload: fileName={}, size={}, category={}, ownerId={}, acl={}",
            fileName, file.getSize(), category, currentUser.userId(), acl);

        String contentType = parseService.detectContentType(file);
        validateContentType(contentType, fileName);

        String fileHash = fileHashService.calculateHash(file);
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            KnowledgeBaseEntity existing = existingKb.get();
            if (!accessService.canRead(existing, currentUser)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "该文件已存在，但当前用户无权访问已有知识库");
            }
            log.info("Duplicate knowledge base detected: hash={}, kbId={}", fileHash, existing.getId());
            return persistenceService.handleDuplicateKnowledgeBase(existing, fileHash);
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
            file,
            name,
            category,
            fileKey,
            fileUrl,
            fileHash,
            currentUser.userId(),
            acl,
            aclUsers,
            aclRoles
        );

        vectorizeStreamProducer.sendVectorizeTask(savedKb.getId(), vectorPayload);

        log.info("Knowledge base upload completed, vector task queued: fileName={}, kbId={}, blocks={}",
            fileName, savedKb.getId(), document.blocks().size());

        Map<String, Object> knowledgeBase = new LinkedHashMap<>();
        knowledgeBase.put("id", savedKb.getId());
        knowledgeBase.put("name", savedKb.getName());
        knowledgeBase.put("category", savedKb.getCategory() != null ? savedKb.getCategory() : "");
        knowledgeBase.put("ownerId", savedKb.getOwnerId());
        knowledgeBase.put("acl", savedKb.getAcl());
        knowledgeBase.put("aclUsers", savedKb.getAclUsers() != null ? savedKb.getAclUsers() : "");
        knowledgeBase.put("aclRoles", savedKb.getAclRoles() != null ? savedKb.getAclRoles() : "");
        knowledgeBase.put("fileSize", savedKb.getFileSize());
        knowledgeBase.put("contentLength", document.indexableLength());
        knowledgeBase.put("blockCount", document.blocks().size());
        knowledgeBase.put("vectorStatus", VectorStatus.PENDING.name());

        return Map.of(
            "knowledgeBase", knowledgeBase,
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
        KnowledgeBaseEntity kb = accessService.requireManageable(kbId);

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