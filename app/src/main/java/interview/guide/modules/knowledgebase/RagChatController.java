package interview.guide.modules.knowledgebase;

import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.UpdateKnowledgeBasesRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.UpdateTitleRequest;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "RAG Chat", description = "Knowledge-base backed chat sessions")
public class RagChatController {

  private final RagChatSessionService sessionService;

  @PostMapping("/api/rag-chat/sessions")
  public Result<SessionDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
    return Result.success(sessionService.createSession(request));
  }

  @GetMapping("/api/rag-chat/sessions")
  public Result<List<SessionListItemDTO>> listSessions() {
    return Result.success(sessionService.listSessions());
  }

  @GetMapping("/api/rag-chat/sessions/{sessionId}")
  public Result<SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId) {
    return Result.success(sessionService.getSessionDetail(sessionId));
  }

  @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
  public Result<Void> updateSessionTitle(
      @PathVariable Long sessionId,
      @Valid @RequestBody UpdateTitleRequest request) {
    sessionService.updateSessionTitle(sessionId, request.title());
    return Result.success(null);
  }

  @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
  public Result<Void> togglePin(@PathVariable Long sessionId) {
    sessionService.togglePin(sessionId);
    return Result.success(null);
  }

  @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
  public Result<Void> updateSessionKnowledgeBases(
      @PathVariable Long sessionId,
      @Valid @RequestBody UpdateKnowledgeBasesRequest request) {
    sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds());
    return Result.success(null);
  }

  @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
  public Result<Void> deleteSession(@PathVariable Long sessionId) {
    sessionService.deleteSession(sessionId);
    return Result.success(null);
  }

  @PostMapping(
      value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE
  )
  public Flux<ServerSentEvent<String>> sendMessageStream(
      @PathVariable Long sessionId,
      @Valid @RequestBody SendMessageRequest request) {
    log.info("RAG chat stream request received: sessionId={}, question={}",
      sessionId, request.question());

    Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());
    StringBuilder fullContent = new StringBuilder();

    return sessionService.getStreamAnswer(sessionId, request.question())
      .doOnNext(fullContent::append)
      .map(chunk -> ServerSentEvent.<String>builder()
        .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
        .build())
      .doOnComplete(() -> {
        sessionService.completeStreamMessage(messageId, fullContent.toString());
        log.info("RAG chat stream completed: sessionId={}, messageId={}", sessionId, messageId);
      })
      .doOnError(e -> {
        String content = !fullContent.isEmpty()
          ? fullContent.toString()
          : "[ERROR] answer generation failed: " + e.getMessage();
        sessionService.completeStreamMessage(messageId, content);
        log.error("RAG chat stream failed: sessionId={}", sessionId, e);
      });
  }
}