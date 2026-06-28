package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserContext;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {

  private final RagChatSessionRepository sessionRepository;
  private final RagChatMessageRepository messageRepository;
  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final KnowledgeBaseQueryService queryService;
  private final RagChatMapper ragChatMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final KnowledgeBaseQueryProperties queryProperties;
  private final KnowledgeBaseAccessService accessService;

  @Transactional
  public SessionDTO createSession(CreateSessionRequest request) {
    CurrentUserContext user = accessService.currentUser();
    List<Long> readableIds = accessService.filterReadableIds(request.knowledgeBaseIds());
    List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository.findAllById(readableIds);

    RagChatSessionEntity session = new RagChatSessionEntity();
    session.setOwnerId(user.userId());
    session.setTitle(request.title() != null && !request.title().isBlank()
      ? request.title()
      : generateTitle(knowledgeBases));
    session.setKnowledgeBases(new HashSet<>(knowledgeBases));

    session = sessionRepository.save(session);
    log.info("RAG chat session created: id={}, ownerId={}, title={}",
      session.getId(), session.getOwnerId(), session.getTitle());

    return ragChatMapper.toSessionDTO(session);
  }

  @Transactional(readOnly = true)
  public List<SessionListItemDTO> listSessions() {
    return sessionRepository.findAllOrderByPinnedAndUpdatedAtDesc()
      .stream()
      .filter(this::canAccessSession)
      .map(ragChatMapper::toSessionListItemDTO)
      .toList();
  }

  @Transactional(readOnly = true)
  public SessionDetailDTO getSessionDetail(Long sessionId) {
    RagChatSessionEntity session = loadSessionWithKnowledgeBases(sessionId);
    requireSessionOwner(session);

    List<RagChatMessageEntity> messages = messageRepository
      .findBySessionIdOrderByMessageOrderAsc(sessionId);

    List<KnowledgeBaseEntity> readableKnowledgeBases = readableKnowledgeBases(session);
    if (readableKnowledgeBases.isEmpty()) {
      throw new BusinessException(ErrorCode.FORBIDDEN,
        "No permission to access the knowledge bases bound to this RAG session");
    }
    List<KnowledgeBaseListItemDTO> kbDTOs = knowledgeBaseMapper
      .toListItemDTOList(readableKnowledgeBases);

    return ragChatMapper.toSessionDetailDTO(session, messages, kbDTOs);
  }

  @Transactional
  public Long prepareStreamMessage(Long sessionId, String question) {
    RagChatSessionEntity session = loadSessionWithKnowledgeBases(sessionId);
    requireSessionOwner(session);
    ensureSessionReadable(session);

    int nextOrder = session.getMessageCount();

    RagChatMessageEntity userMessage = new RagChatMessageEntity();
    userMessage.setSession(session);
    userMessage.setType(RagChatMessageEntity.MessageType.USER);
    userMessage.setContent(question);
    userMessage.setMessageOrder(nextOrder);
    userMessage.setCompleted(true);
    messageRepository.save(userMessage);

    RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
    assistantMessage.setSession(session);
    assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
    assistantMessage.setContent("");
    assistantMessage.setMessageOrder(nextOrder + 1);
    assistantMessage.setCompleted(false);
    assistantMessage = messageRepository.save(assistantMessage);

    session.setMessageCount(nextOrder + 2);
    sessionRepository.save(session);

    log.info("RAG stream message prepared: sessionId={}, messageId={}",
      sessionId, assistantMessage.getId());

    return assistantMessage.getId();
  }

  @Transactional
  public void completeStreamMessage(Long messageId, String content) {
    RagChatMessageEntity message = messageRepository.findById(messageId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Message not found"));

    RagChatSessionEntity session = message.getSession();
    if (session != null) {
      requireSessionOwner(session);
    }

    message.setContent(content);
    message.setCompleted(true);
    messageRepository.save(message);

    log.info("RAG stream message completed: messageId={}, contentLength={}",
      messageId, content.length());
  }

  @Transactional(readOnly = true)
  public Flux<String> getStreamAnswer(Long sessionId, String question) {
    RagChatSessionEntity session = loadSessionWithKnowledgeBases(sessionId);
    requireSessionOwner(session);

    List<Long> kbIds = accessService.filterReadableIds(session.getKnowledgeBaseIds());
    List<Message> history = queryProperties.getHistory().isEnabled()
      ? loadHistoryMessages(sessionId)
      : List.of();

    log.info("RAG chat history loaded: sessionId={}, historySize={}", sessionId, history.size());
    return queryService.answerQuestionStream(kbIds, question, history);
  }

  @Transactional
  public void updateSessionTitle(Long sessionId, String title) {
    RagChatSessionEntity session = loadSession(sessionId);
    requireSessionOwner(session);

    session.setTitle(title);
    sessionRepository.save(session);

    log.info("RAG chat session title updated: sessionId={}, title={}", sessionId, title);
  }

  @Transactional
  public void togglePin(Long sessionId) {
    RagChatSessionEntity session = loadSession(sessionId);
    requireSessionOwner(session);

    Boolean currentPinned = session.getIsPinned() != null ? session.getIsPinned() : false;
    session.setIsPinned(!currentPinned);
    sessionRepository.save(session);

    log.info("RAG chat session pin toggled: sessionId={}, isPinned={}",
      sessionId, session.getIsPinned());
  }

  @Transactional
  public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
    RagChatSessionEntity session = loadSession(sessionId);
    requireSessionOwner(session);

    List<Long> readableIds = accessService.filterReadableIds(knowledgeBaseIds);
    List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository.findAllById(readableIds);

    session.setKnowledgeBases(new HashSet<>(knowledgeBases));
    sessionRepository.save(session);

    log.info("RAG chat session knowledge bases updated: sessionId={}, kbIds={}",
      sessionId, readableIds);
  }

  @Transactional
  public void deleteSession(Long sessionId) {
    RagChatSessionEntity session = loadSession(sessionId);
    requireSessionOwner(session);

    sessionRepository.delete(session);
    log.info("RAG chat session deleted: sessionId={}", sessionId);
  }

  private List<Message> loadHistoryMessages(Long sessionId) {
    int limit = queryProperties.getHistory().getMaxMessages() + 1;
    List<RagChatMessageEntity> recent = messageRepository
      .findRecentCompletedBySessionId(sessionId, PageRequest.of(0, limit));

    if (recent.isEmpty()) {
      return List.of();
    }

    List<RagChatMessageEntity> historyMessages = recent.size() <= 1
      ? List.of()
      : recent.subList(1, recent.size());

    return historyMessages.reversed().stream()
      .map(message -> message.getType() == RagChatMessageEntity.MessageType.USER
        ? (Message) new UserMessage(message.getContent())
        : (Message) new AssistantMessage(message.getContent()))
      .toList();
  }

  private RagChatSessionEntity loadSession(Long sessionId) {
    return sessionRepository.findById(sessionId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "RAG session not found"));
  }

  private RagChatSessionEntity loadSessionWithKnowledgeBases(Long sessionId) {
    return sessionRepository.findByIdWithKnowledgeBases(sessionId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "RAG session not found"));
  }

  private void ensureSessionReadable(RagChatSessionEntity session) {
    if (readableKnowledgeBases(session).isEmpty()) {
      throw new BusinessException(ErrorCode.FORBIDDEN,
        "No permission to access the knowledge bases bound to this RAG session");
    }
  }

  private boolean canAccessSession(RagChatSessionEntity session) {
    return isSessionOwner(session, accessService.currentUser())
      && !readableKnowledgeBases(session).isEmpty();
  }

  private void requireSessionOwner(RagChatSessionEntity session) {
    if (!isSessionOwner(session, accessService.currentUser())) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "No permission to access this RAG session");
    }
  }

  private boolean isSessionOwner(RagChatSessionEntity session, CurrentUserContext user) {
    return user.isAdmin()
      || CurrentUserContext.normalizeUserId(session.getOwnerId()).equals(user.userId());
  }

  private List<KnowledgeBaseEntity> readableKnowledgeBases(RagChatSessionEntity session) {
    return accessService.filterReadable(new java.util.ArrayList<>(session.getKnowledgeBases()));
  }

  private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
    if (knowledgeBases.isEmpty()) {
      return "New chat";
    }
    if (knowledgeBases.size() == 1) {
      return knowledgeBases.getFirst().getName();
    }
    return knowledgeBases.size() + " knowledge bases chat";
  }
}