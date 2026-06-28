package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBasePersistenceService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseAccessService accessService;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleDuplicateKnowledgeBase(KnowledgeBaseEntity kb, String fileHash) {
        log.info("Duplicate knowledge base detected: kbId={}, hash={}", kb.getId(), fileHash);

        kb.incrementAccessCount();
        knowledgeBaseRepository.save(kb);

        return Map.of(
            "knowledgeBase", Map.of(
                "id", kb.getId(),
                "name", kb.getName(),
                "ownerId", kb.getOwnerId(),
                "acl", kb.getAcl(),
                "aclUsers", kb.getAclUsers() != null ? kb.getAclUsers() : "",
                "aclRoles", kb.getAclRoles() != null ? kb.getAclRoles() : "",
                "fileSize", kb.getFileSize(),
                "contentLength", 0
            ),
            "storage", Map.of(
                "fileKey", kb.getStorageKey() != null ? kb.getStorageKey() : "",
                "fileUrl", kb.getStorageUrl() != null ? kb.getStorageUrl() : ""
            ),
            "duplicate", true
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseEntity saveKnowledgeBase(
        MultipartFile file,
        String name,
        String category,
        String storageKey,
        String storageUrl,
        String fileHash,
        String ownerId,
        String acl,
        String aclUsers,
        String aclRoles
    ) {
        try {
            KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
            kb.setFileHash(fileHash);
            kb.setName(name != null && !name.trim().isEmpty()
                ? name.trim()
                : extractNameFromFilename(file.getOriginalFilename()));
            kb.setCategory(category != null && !category.trim().isEmpty() ? category.trim() : null);
            kb.setOwnerId(accessService.normalizeOwner(ownerId));
            kb.setAcl(accessService.normalizeAcl(acl));
            kb.setAclUsers(accessService.normalizeCsv(aclUsers));
            kb.setAclRoles(accessService.normalizeRolesCsv(aclRoles));
            kb.setOriginalFilename(file.getOriginalFilename());
            kb.setFileSize(file.getSize());
            kb.setContentType(file.getContentType());
            kb.setStorageKey(storageKey);
            kb.setStorageUrl(storageUrl);

            KnowledgeBaseEntity saved = knowledgeBaseRepository.save(kb);
            log.info("Knowledge base saved: id={}, name={}, ownerId={}, acl={}, category={}, hash={}",
                saved.getId(), saved.getName(), saved.getOwnerId(), saved.getAcl(), saved.getCategory(), fileHash);
            return saved;
        } catch (Exception e) {
            log.error("Save knowledge base failed: fileHash={}", fileHash, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存知识库失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateVectorStatusToPending(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        kb.setVectorStatus(VectorStatus.PENDING);
        kb.setVectorError(null);
        knowledgeBaseRepository.save(kb);

        log.info("Knowledge base vector status updated to PENDING: kbId={}", kbId);
    }

    private String extractNameFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "未命名知识库";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }
        return filename;
    }
}