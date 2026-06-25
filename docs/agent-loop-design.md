# 项目是如何构建自己的 Agent Loop 的

这个项目没有做成 LangChain 那种完全通用的 `while(true)` 自主 Agent 框架，而是做了一个更贴合业务的“领域型 Agent Loop”。核心思路是：后端 Service 负责状态编排、上下文组装、工具/知识检索、结果校验和落库，Spring AI 负责模型调用、Tool Calling 和流式输出。

一句话概括：模型不是直接裸调，而是每一轮都经过“接收输入 -> 读取会话状态 -> 组装上下文 -> 检索知识/加载 Skill -> 调用 LLM -> 解析或流式返回 -> 更新状态/落库 -> 进入下一轮”的闭环。

## 1. 总体 Agent Loop

项目里的 Agent Loop 可以抽象成下面几个步骤：

1. **Observe：接收用户输入**
   - 普通 RAG 问答接收用户问题。
   - 模拟面试接收简历、JD、面试方向、难度和题目数量。
   - 语音面试通过 WebSocket 接收实时音频，再经过 ASR 转成文字。

2. **Load State：加载当前状态**
   - 从数据库读取会话、历史消息、简历、面试阶段、题目记录。
   - 语音面试还会从 Redis 缓存中读取活跃会话状态。

3. **Build Context：构造上下文**
   - 拼接系统 Prompt、用户输入、历史对话、简历内容、JD 内容。
   - RAG 场景会先做 query rewrite，再去 pgvector 做相似度检索。
   - 面试场景会根据 Skill 配置加载 `SKILL.md`、`skill.meta.yml` 和 references。

4. **Act：调用模型或工具**
   - 通过 `LlmProviderRegistry` 获取 ChatClient。
   - 普通结构化输出使用 plain ChatClient，避免工具调用污染 JSON。
   - 语音面试使用 voice ChatClient，挂载 `SkillsTool` 和 `ToolCallAdvisor`。

5. **Validate / Parse：校验模型输出**
   - 出题、JD 解析等结构化结果通过 `StructuredOutputInvoker + BeanOutputConverter` 解析。
   - 解析失败后会按配置重试，并把上次错误注入到修复型 Prompt 中。

6. **Update State：更新状态**
   - RAG 问答保存用户消息和 AI 消息。
   - 语音面试保存用户识别文本、AI 回复、当前阶段。
   - 面试结束后通过 Redis Stream 触发异步评估。

7. **Next Turn：进入下一轮**
   - RAG 聊天继续带历史上下文。
   - 语音面试继续等待用户下一段语音。
   - 面试流程根据题目数量、阶段规则或用户控制进入下一步。

## 2. ChatClient 和工具调用是 Agent Loop 的底座

核心入口在 `LlmProviderRegistry`。

- `getChatClientOrDefault()`：普通模型调用，支持多 Provider。
- `getPlainChatClient()`：结构化输出专用，不挂工具，避免 JSON 结果被工具调用内容干扰。
- `getVoiceChatClient()`：语音面试专用，挂载 `SkillsTool + ToolCallAdvisor`，支持模型在对话中加载面试 Skill。
- `buildDefaultAdvisors()`：统一配置 ToolCall、Memory、Logger、SafeGuard 等 Advisor。

相关代码：

- `app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java`
- `app/src/main/java/interview/guide/common/ai/AgentUtilsConfiguration.java`
- `app/src/main/resources/application.yml`

`AgentUtilsConfiguration` 会把 `resources/skills` 注册成 Spring AI 的 `SkillsTool`。也就是说，模型在需要某个面试方向的人设和规则时，可以通过工具加载对应的 `SKILL.md`。

不过这个项目没有把所有逻辑都交给模型自己规划。真正的流程控制仍然在 Java Service 里，这是这个项目比较务实的地方。

## 3. Skill 驱动的出题 Agent Loop

出题流程主要由 `InterviewSkillService` 和 `InterviewQuestionService` 组成。

### 3.1 Skill 加载

项目启动时，`InterviewSkillService` 会扫描：

- `resources/skills/{skillId}/SKILL.md`
- `resources/skills/{skillId}/skill.meta.yml`
- references 参考资料

`SKILL.md` 负责定义面试官 persona 和出题规则，`skill.meta.yml` 负责定义分类、优先级和参考资料映射。

### 3.2 JD 自定义面试

如果用户上传 JD，项目会先让模型解析 JD，提取面试方向分类，然后构造一个临时的 custom skill。

流程是：

1. 用户输入 JD。
2. `parseJd()` 使用 Prompt + `BeanOutputConverter` 让模型返回结构化分类。
3. 后端校验分类结果。
4. `buildCustomSkill()` 把解析结果转换成临时 Skill。
5. 后续出题逻辑复用普通 Skill 流程。

### 3.3 生成题目

`InterviewQuestionService.generateQuestionsBySkill()` 会根据是否有简历走不同分支：

- 没有简历：按 Skill 方向直接出题。
- 有简历：并行生成“简历题”和“方向题”，默认简历题占 60%。

它还会做几件事情：

- 根据分类优先级分配题目数量。
- 注入 references 作为参考资料。
- 注入历史题目，避免重复出题。
- 使用 `StructuredOutputInvoker` 要求模型返回结构化题目列表。
- 失败时降级到 fallback questions。

这里的 Loop 不是多轮自主循环，而是一个“出题规划 -> 上下文增强 -> 模型生成 -> 结构化解析 -> 失败降级”的单轮 Agent 流程。

## 4. RAG 问答 Agent Loop

RAG 问答主要在 `KnowledgeBaseQueryService` 和 `RagChatSessionService`。

每一轮问答的流程是：

1. 前端提交问题。
2. `RagChatSessionService.prepareStreamMessage()` 先保存用户消息，并创建一个 AI 消息占位。
3. 如果开启历史上下文，加载最近若干轮消息。
4. `KnowledgeBaseQueryService` 对问题做 query rewrite。
5. 使用改写后的问题和原问题分别尝试向量检索。
6. 调用 pgvector 相似度搜索，拿到相关文档片段。
7. 拼接 RAG Prompt：系统约束 + 检索上下文 + 用户问题。
8. 调用 ChatClient 流式输出。
9. 输出完成后 `completeStreamMessage()` 把 AI 回答落库。

这里的 Agent Loop 体现为“问题改写 -> 检索证据 -> 带证据回答 -> 保存历史 -> 下轮继续使用历史”。

需要注意：项目没有把 RAG 记忆完全交给 Spring AI 的 Memory Advisor。配置里 `message-chat-memory-enabled` 默认是 false，项目主要是自己用数据库维护会话历史，再手动注入到 Prompt。

## 5. 实时语音面试 Agent Loop

语音面试是项目里最像完整 Agent Loop 的部分，入口在 `VoiceInterviewWebSocketHandler`。

完整流程如下：

1. 前端建立 WebSocket 连接。
2. 后端启动 DashScope ASR 实时识别。
3. 用户语音以音频块形式发送到后端。
4. 后端把音频转发给 ASR。
5. ASR 返回 partial/final 文本。
6. 用户点击提交或满足提交条件后，后端合并本轮用户发言。
7. `triggerLlmResponse()` 读取会话、阶段、历史消息。
8. `DashscopeLlmService` 构造系统 Prompt 和用户 Prompt。
9. `LlmProviderRegistry.getVoiceChatClient()` 获取带 `SkillsTool + ToolCallAdvisor` 的 ChatClient。
10. LLM 流式生成面试官回复。
11. 每检测到完整句子，就并发触发 TTS，边生成文本边合成语音。
12. 后端通过 WebSocket 把字幕、文本、音频推给前端。
13. 用户文本和 AI 回复落库。
14. 继续等待下一轮用户语音。
15. 用户结束面试后，写入完成状态，并通过 Redis Stream 触发异步评估。

这个流程可以看成一个实时 Agent Loop：

```text
Audio Input
  -> ASR
  -> Merge User Utterance
  -> Load Session + History + Skill
  -> LLM Reasoning / Question Generation
  -> Streaming Text
  -> TTS
  -> WebSocket Output
  -> Persist Message
  -> Wait Next Turn
```

项目还做了一些工程化处理：

- 使用虚拟线程执行 LLM/TTS/JDBC 等可能阻塞的任务。
- 使用 `SessionState` 防止同一会话重复触发 LLM。
- AI 正在说话或冷却期内丢弃麦克风输入，避免回声再次触发 STT。
- 流式 LLM 输出时按句子触发 TTS，降低首段语音等待时间。
- TTS 失败时可以回退到整段文本合成。

## 6. 这个设计为什么叫“自己的 Agent Loop”

它不是完全依赖框架默认链路，而是把 Agent 的关键控制点写在业务代码里：

- **状态自己管**：会话、阶段、历史、消息都落到数据库或 Redis。
- **上下文自己组装**：Prompt、Skill、简历、JD、RAG 证据由后端明确拼接。
- **工具自己注册**：通过 `SkillsTool` 把本地 `SKILL.md` 暴露给模型。
- **输出自己校验**：结构化结果用 `BeanOutputConverter` 解析，失败重试。
- **失败自己降级**：出题失败有 fallback，RAG 无命中有固定回复，语音 TTS 有兜底。
- **异步自己编排**：面试评估、向量化等耗时任务走 Redis Stream。

所以它更准确的说法是：项目构建了一个“业务驱动的 Agent Loop”，Spring AI 只是其中的模型和工具调用层。

## 7. 面试时可以这样说

如果面试官问“你们项目是怎么做 Agent Loop 的”，可以这样回答：

> 我这个项目没有直接套一个通用 Agent 框架，而是在业务层自己实现了一个领域型 Agent Loop。每一轮请求进来后，后端先读取会话状态，比如当前面试阶段、历史对话、简历、JD 和知识库；然后根据场景做上下文增强，比如 RAG 检索知识片段、加载本地 Skill、拼接 Prompt；接着通过 Spring AI 的 ChatClient 调用模型，语音面试场景还会挂载 SkillsTool 和 ToolCallAdvisor，让模型按需加载面试官角色；模型输出后，结构化场景会用 BeanOutputConverter 做解析和重试，语音场景会流式输出并触发 TTS；最后把本轮消息、阶段状态和评估任务落库或写入 Redis Stream。这样整个系统不是简单调一次大模型，而是形成了“输入感知、状态读取、上下文增强、模型决策、结果校验、状态更新、下一轮继续”的闭环。

更简短一点：

> 我们的 Agent Loop 是后端业务编排出来的，不是完全交给模型自由规划。核心流程是 Observe、Context Build、RAG/Skill Tool Augment、LLM Call、Parse/Validate、Persist State、Next Turn。RAG 问答、JD 出题和实时语音面试都复用了这个思路，只是上下文来源和输出方式不同。

## 8. 注意不要夸大的点

面试时不要说这个项目已经实现了完整的通用自主 Agent，因为代码里没有看到复杂的 Planner、任务分解器、长期自主执行器或 MCP 工具生态。

更准确的表达是：

- 有 Tool Calling：主要用于 `SkillsTool` 加载本地 Skill。
- 有 RAG：用于知识库问答。
- 有结构化输出：用于 JD 解析、出题等场景。
- 有多轮记忆：主要靠数据库保存会话历史。
- 有实时语音 Agent Loop：ASR -> LLM -> TTS -> WebSocket。
- 有业务状态机：语音面试阶段切换、会话状态、评估状态。

这套设计的亮点在于：没有把所有决策都交给大模型，而是用 Java 后端把可控性、稳定性和工程兜底补上了。
