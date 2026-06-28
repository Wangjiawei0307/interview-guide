package interview.guide.modules.knowledgebase.model;

import interview.guide.common.security.CurrentUserContext;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "rag_chat_sessions", indexes = {
  @Index(name = "idx_rag_session_updated", columnList = "updatedAt")
})
@Getter
@Setter
@NoArgsConstructor
public class RagChatSessionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String title;

  @Column(length = 128)
  private String ownerId = CurrentUserContext.ANONYMOUS_USER_ID;

  @Enumerated(EnumType.STRING)
  @Column(length = 20)
  private SessionStatus status = SessionStatus.ACTIVE;

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
    name = "rag_session_knowledge_bases",
    joinColumns = @JoinColumn(name = "session_id"),
    inverseJoinColumns = @JoinColumn(name = "knowledge_base_id")
  )
  private Set<KnowledgeBaseEntity> knowledgeBases = new HashSet<>();

  @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("messageOrder ASC")
  private List<RagChatMessageEntity> messages = new ArrayList<>();

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;

  private Integer messageCount = 0;

  @Column(columnDefinition = "boolean default false")
  private Boolean isPinned = false;

  public enum SessionStatus {
    ACTIVE,
    ARCHIVED
  }

  @PrePersist
  protected void onCreate() {
    ensureDefaults();
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
  }

  @PreUpdate
  protected void onUpdate() {
    ensureDefaults();
    updatedAt = LocalDateTime.now();
  }

  @PostLoad
  protected void onLoad() {
    ensureDefaults();
  }

  public void addMessage(RagChatMessageEntity message) {
    messages.add(message);
    message.setSession(this);
    messageCount = messages.size();
    updatedAt = LocalDateTime.now();
  }

  public List<Long> getKnowledgeBaseIds() {
    return knowledgeBases.stream()
      .map(KnowledgeBaseEntity::getId)
      .toList();
  }

  private void ensureDefaults() {
    ownerId = CurrentUserContext.normalizeUserId(ownerId);
    if (messageCount == null) {
      messageCount = 0;
    }
    if (isPinned == null) {
      isPinned = false;
    }
    if (status == null) {
      status = SessionStatus.ACTIVE;
    }
  }
}