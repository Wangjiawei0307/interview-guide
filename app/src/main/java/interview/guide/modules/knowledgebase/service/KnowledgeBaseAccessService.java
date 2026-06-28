package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserContext;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseAcl;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseAccessService {

    public static final String META_ACL = "acl";
    public static final String META_OWNER_ID = "acl_owner_id";
    public static final String META_READ_USERS = "acl_read_users";
    public static final String META_READ_ROLES = "acl_read_roles";

    private final CurrentUserProvider currentUserProvider;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public CurrentUserContext currentUser() {
        return currentUserProvider.currentUser();
    }

    public List<KnowledgeBaseEntity> filterReadable(List<KnowledgeBaseEntity> entities) {
        CurrentUserContext user = currentUser();
        return entities.stream()
            .filter(entity -> canRead(entity, user))
            .toList();
    }

    public List<Long> filterReadableIds(List<Long> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return List.of();
        }
        List<Long> uniqueIds = requestedIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        List<KnowledgeBaseEntity> existing = knowledgeBaseRepository.findAllById(uniqueIds);
        if (existing.size() != uniqueIds.size()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "部分知识库不存在");
        }
        CurrentUserContext user = currentUser();
        List<Long> readableIds = existing.stream()
            .filter(entity -> canRead(entity, user))
            .map(KnowledgeBaseEntity::getId)
            .toList();
        if (readableIds.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问所选知识库");
        }
        return readableIds;
    }

    public KnowledgeBaseEntity requireReadable(Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        if (!canRead(entity, currentUser())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该知识库");
        }
        return entity;
    }

    public KnowledgeBaseEntity requireManageable(Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        if (!canManage(entity, currentUser())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该知识库");
        }
        return entity;
    }

    public void assertReadable(List<Long> ids) {
        filterReadableIds(ids);
    }

    public boolean canRead(KnowledgeBaseEntity entity, CurrentUserContext user) {
        if (entity == null) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        KnowledgeBaseAcl acl = KnowledgeBaseAcl.from(entity.getAcl());
        if (acl == KnowledgeBaseAcl.PUBLIC) {
            return true;
        }
        if (isOwner(entity.getOwnerId(), user)) {
            return true;
        }
        if (acl == KnowledgeBaseAcl.SHARED) {
            return containsToken(entity.getAclUsers(), user.userId())
                || intersectsRoles(entity.getAclRoles(), user.roles());
        }
        return false;
    }

    public boolean canManage(KnowledgeBaseEntity entity, CurrentUserContext user) {
        return entity != null && (user.isAdmin() || isOwner(entity.getOwnerId(), user));
    }

    public boolean canReadDocument(Document document) {
        if (document == null) {
            return false;
        }
        return canReadMetadata(document.getMetadata(), currentUser());
    }

    public Map<String, String> buildAclMetadata(KnowledgeBaseEntity entity) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(META_ACL, KnowledgeBaseAcl.from(entity.getAcl()).name());
        metadata.put(META_OWNER_ID, normalizeOwner(entity.getOwnerId()));
        metadata.put(META_READ_USERS, normalizeCsv(entity.getAclUsers()));
        metadata.put(META_READ_ROLES, normalizeRolesCsv(entity.getAclRoles()));
        return metadata;
    }

    public String normalizeAcl(String acl) {
        return KnowledgeBaseAcl.from(acl).name();
    }

    public String normalizeOwner(String ownerId) {
        return CurrentUserContext.normalizeUserId(ownerId);
    }

    public String normalizeCsv(String values) {
        return joinTokens(splitCsv(values));
    }

    public String normalizeRolesCsv(String values) {
        Set<String> roles = new LinkedHashSet<>();
        for (String role : splitCsv(values)) {
            String normalized = CurrentUserContext.normalizeRole(role);
            if (!normalized.isBlank()) {
                roles.add(normalized);
            }
        }
        return String.join(",", roles);
    }

    private boolean canReadMetadata(Map<String, Object> metadata, CurrentUserContext user) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey(META_ACL)) {
            return true;
        }
        if (user.isAdmin()) {
            return true;
        }
        KnowledgeBaseAcl acl = KnowledgeBaseAcl.from(stringValue(metadata.get(META_ACL)));
        if (acl == KnowledgeBaseAcl.PUBLIC) {
            return true;
        }
        String ownerId = stringValue(metadata.get(META_OWNER_ID));
        if (isOwner(ownerId, user)) {
            return true;
        }
        if (acl == KnowledgeBaseAcl.SHARED) {
            return containsToken(stringValue(metadata.get(META_READ_USERS)), user.userId())
                || intersectsRoles(stringValue(metadata.get(META_READ_ROLES)), user.roles());
        }
        return false;
    }

    private boolean isOwner(String ownerId, CurrentUserContext user) {
        return normalizeOwner(ownerId).equals(user.userId());
    }

    private boolean containsToken(String csv, String value) {
        return splitCsv(csv).contains(value);
    }

    private boolean intersectsRoles(String csv, Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        Set<String> allowedRoles = new LinkedHashSet<>();
        for (String role : splitCsv(csv)) {
            allowedRoles.add(CurrentUserContext.normalizeRole(role));
        }
        for (String role : roles) {
            if (allowedRoles.contains(CurrentUserContext.normalizeRole(role))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> splitCsv(String values) {
        if (values == null || values.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(values.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .forEach(result::add);
        return result;
    }

    private String joinTokens(Set<String> tokens) {
        return String.join(",", new ArrayList<>(tokens));
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}