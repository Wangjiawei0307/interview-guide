package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 知识库查询服务，负责 RAG 问答链路编排。
 *
 * <p>主流程：问题清洗 -> 可选 query rewrite -> 向量召回 topN -> rerank 精排 -> 拼接上下文 -> LLM 回答。
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {

    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    private final LlmProviderRegistry llmProviderRegistry;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRerankService rerankService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final KnowledgeBaseAccessService accessService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final boolean historyEnabled;
    private final int maxHistoryMessages;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;
    private final int finalTopkShort;
    private final int finalTopkMedium;
    private final int finalTopkLong;

    public KnowledgeBaseQueryService(
        LlmProviderRegistry llmProviderRegistry,
        KnowledgeBaseVectorService vectorService,
        KnowledgeBaseRerankService rerankService,
        KnowledgeBaseListService listService,
        KnowledgeBaseCountService countService,
        KnowledgeBaseAccessService accessService,
        KnowledgeBaseQueryProperties queryProperties,
        ResourceLoader resourceLoader
    ) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.vectorService = vectorService;
        this.rerankService = rerankService;
        this.listService = listService;
        this.countService = countService;
        this.accessService = accessService;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.historyEnabled = queryProperties.getHistory().isEnabled();
        this.maxHistoryMessages = queryProperties.getHistory().getMaxMessages();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
        this.finalTopkShort = queryProperties.getRerank().getFinalTopkShort();
        this.finalTopkMedium = queryProperties.getRerank().getFinalTopkMedium();
        this.finalTopkLong = queryProperties.getRerank().getFinalTopkLong();
    }

    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        List<Long> readableIds = accessService.filterReadableIds(knowledgeBaseIds);
        countService.updateQuestionCounts(readableIds);

        QueryContext queryContext = buildQueryContext(question, List.of());
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, readableIds);
        if (!hasEffectiveHit(relevantDocs)) {
            return NO_RESULT_RESPONSE;
        }

        String context = buildContext(relevantDocs);
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        try {
            String answer = getChatClient().prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return normalizeAnswer(answer);
        } catch (Exception e) {
            log.error("知识库问答失败", e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "知识库查询失败：" + e.getMessage());
        }
    }

    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        List<Long> readableIds = accessService.filterReadableIds(request.knowledgeBaseIds());
        String answer = answerQuestion(readableIds, request.question());
        List<String> kbNames = listService.getKnowledgeBaseNames(readableIds);
        String kbNamesStr = String.join("、", kbNames);
        Long primaryKbId = readableIds.getFirst();
        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }

    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}",
            knowledgeBaseIds, question, history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            List<Long> readableIds = accessService.filterReadableIds(knowledgeBaseIds);
            countService.updateQuestionCounts(readableIds);

            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, readableIds);
            if (!hasEffectiveHit(relevantDocs)) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            String context = buildContext(relevantDocs);
            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }

            Flux<String> responseFlux = promptSpec
                .user(userPrompt)
                .stream()
                .content();

            log.info("开始流式输出知识库回答: kbIds={}", knowledgeBaseIds);
            return normalizeStreamOutput(responseFlux)
                .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}", knowledgeBaseIds, e);
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });
        } catch (Exception e) {
            log.error("知识库流式问答失败", e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getDefaultChatClient();
    }

    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    private String buildContext(List<Document> relevantDocs) {
        return relevantDocs.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n---\n\n"));
    }

    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
    }

    private List<Message> sanitizeHistory(List<Message> history) {
        if (!historyEnabled || history == null || history.isEmpty()) {
            return List.of();
        }
        if (history.size() <= maxHistoryMessages) {
            return history;
        }
        return history.subList(history.size() - maxHistoryMessages, history.size());
    }

    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            List<Document> docs = vectorService.similaritySearch(
                candidateQuery,
                knowledgeBaseIds,
                queryContext.searchParams().topK(),
                queryContext.searchParams().minScore()
            );
            List<Document> rerankedDocs = rerankService.rerank(
                buildRerankQuery(queryContext, candidateQuery),
                docs,
                queryContext.searchParams().finalTopK()
            );
            log.info("检索候选 query='{}'，向量命中 {} 条，rerank 后返回 {} 条",
                candidateQuery, docs.size(), rerankedDocs.size());
            if (hasEffectiveHit(rerankedDocs)) {
                return rerankedDocs;
            }
        }
        return List.of();
    }

    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, finalTopkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, finalTopkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, finalTopkLong, minScoreDefault);
    }

    private String buildRerankQuery(QueryContext queryContext, String candidateQuery) {
        if (candidateQuery.equals(queryContext.originalQuestion())) {
            return candidateQuery;
        }
        return queryContext.originalQuestion() + "\n" + candidateQuery;
    }

    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info("Query rewrite: origin='{}', rewritten='{}', historySize={}", question, normalized, history.size());
            return normalized;
        } catch (Exception e) {
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }

    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }

                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                sink::error,
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    private record SearchParams(int topK, int finalTopK, double minScore) {
    }

    private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams) {
    }
}