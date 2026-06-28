package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;
    private final KnowledgeBaseAccessService accessService;

    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy) {
        List<KnowledgeBaseEntity> entities = vectorStatus != null
            ? knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(vectorStatus)
            : knowledgeBaseRepository.findAllByOrderByUploadedAtDesc();

        if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
            entities = sortEntities(entities, sortBy);
        }

        return knowledgeBaseMapper.toListItemDTOList(accessService.filterReadable(entities));
    }

    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return listKnowledgeBases(null, null);
    }

    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return listKnowledgeBases(vectorStatus, null);
    }

    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        return Optional.of(knowledgeBaseMapper.toListItemDTO(accessService.requireReadable(id)));
    }

    public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
        return Optional.of(accessService.requireReadable(id));
    }

    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        List<Long> readableIds = accessService.filterReadableIds(ids);
        return readableIds.stream()
            .map(id -> knowledgeBaseRepository.findById(id)
                .map(KnowledgeBaseEntity::getName)
                .orElse("未知知识库"))
            .toList();
    }

    public List<String> getAllCategories() {
        return accessService.filterReadable(knowledgeBaseRepository.findAll()).stream()
            .map(KnowledgeBaseEntity::getCategory)
            .filter(category -> category != null && !category.isBlank())
            .distinct()
            .sorted()
            .toList();
    }

    public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
        List<KnowledgeBaseEntity> entities = category == null || category.isBlank()
            ? knowledgeBaseRepository.findByCategoryIsNullOrderByUploadedAtDesc()
            : knowledgeBaseRepository.findByCategoryOrderByUploadedAtDesc(category);
        return knowledgeBaseMapper.toListItemDTOList(accessService.filterReadable(entities));
    }

    @Transactional
    public void updateCategory(Long id, String category) {
        KnowledgeBaseEntity entity = accessService.requireManageable(id);
        entity.setCategory(category != null && !category.isBlank() ? category : null);
        knowledgeBaseRepository.save(entity);
        log.info("Knowledge base category updated: id={}, category={}", id, category);
    }

    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listKnowledgeBases();
        }
        return knowledgeBaseMapper.toListItemDTOList(
            accessService.filterReadable(knowledgeBaseRepository.searchByKeyword(keyword.trim()))
        );
    }

    public List<KnowledgeBaseListItemDTO> listSorted(String sortBy) {
        return listKnowledgeBases(null, sortBy);
    }

    private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "size" -> entities.stream()
                .sorted((a, b) -> Long.compare(nullToZero(b.getFileSize()), nullToZero(a.getFileSize())))
                .toList();
            case "access" -> entities.stream()
                .sorted((a, b) -> Integer.compare(nullToZero(b.getAccessCount()), nullToZero(a.getAccessCount())))
                .toList();
            case "question" -> entities.stream()
                .sorted((a, b) -> Integer.compare(nullToZero(b.getQuestionCount()), nullToZero(a.getQuestionCount())))
                .toList();
            default -> entities;
        };
    }

    public KnowledgeBaseStatsDTO getStatistics() {
        List<KnowledgeBaseEntity> readable = accessService.filterReadable(knowledgeBaseRepository.findAll());
        long questionCount = readable.stream()
            .map(KnowledgeBaseEntity::getQuestionCount)
            .filter(Objects::nonNull)
            .mapToLong(Integer::longValue)
            .sum();
        long accessCount = readable.stream()
            .map(KnowledgeBaseEntity::getAccessCount)
            .filter(Objects::nonNull)
            .mapToLong(Integer::longValue)
            .sum();
        long completed = readable.stream()
            .filter(kb -> kb.getVectorStatus() == VectorStatus.COMPLETED)
            .count();
        long processing = readable.stream()
            .filter(kb -> kb.getVectorStatus() == VectorStatus.PROCESSING)
            .count();
        return new KnowledgeBaseStatsDTO(readable.size(), questionCount, accessCount, completed, processing);
    }

    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity entity = accessService.requireReadable(id);
        String storageKey = entity.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在");
        }

        log.info("Download knowledge base file: id={}, filename={}", id, entity.getOriginalFilename());
        return fileStorageService.downloadFile(storageKey);
    }

    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return accessService.requireReadable(id);
    }

    private long nullToZero(Long value) {
        return value == null ? 0 : value;
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}