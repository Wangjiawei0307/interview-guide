# 熔断、降级、重试和错误类型总结

## 1. 先说结论

这个项目没有接入 Resilience4j、Sentinel、Spring Cloud CircuitBreaker 这种标准“熔断器”框架，也没有看到 `@CircuitBreaker` 这类注解。

但项目做了不少工程上的保护机制：

- **限流**：用 `@RateLimit + AOP + Redis Lua`，在入口处挡住高频请求。
- **统一异常处理**：所有 Controller 异常统一转换成 `Result.error(code, message)`。
- **结构化输出重试**：模型 JSON 解析失败后，使用修复型 Prompt 重试。
- **异步任务重试**：Redis Stream 消费失败后重新入队，最多重试 3 次。
- **业务降级**：出题失败回退到方向题或默认题，RAG 检索失败返回友好错误，语音 TTS 失败回退到整段合成。
- **状态标记**：简历分析、知识库向量化、面试评估、语音评估失败后都会写入 `FAILED` 和错误信息。

所以面试时不要说“用了 Sentinel 做熔断”，更准确的说法是：项目没有引入标准熔断框架，但在关键链路上做了限流、重试、降级兜底、异常归一化和失败状态持久化。

## 2. 限流算不算熔断？

严格来说，限流不是熔断。

- **限流**：请求还没进核心业务前，先限制访问频率，防止系统被打爆。
- **熔断**：下游服务持续失败后，短时间内不再调用它，直接快速失败或走兜底逻辑。

项目里有明确的限流，但没有标准熔断状态机。

限流实现：

- `@RateLimit` 标注接口。
- `RateLimitAspect` 拦截方法调用。
- 执行 `scripts/rate_limit_single.lua`。
- 超过阈值抛出 `RateLimitExceededException`。
- 全局异常处理器返回 `RATE_LIMIT_EXCEEDED(8001)`。

## 3. AI 服务失败怎么处理？

### 3.1 同步接口统一返回业务错误

`GlobalExceptionHandler` 会处理 AI 网络异常和调用异常：

- `ResourceAccessException`
  - 如果底层是 `SocketTimeoutException`，返回 `AI_SERVICE_TIMEOUT(7002)`。
  - 如果包含 handshake，返回 `AI_SERVICE_UNAVAILABLE(7001)`。
  - 其他网络连接问题也返回 `AI_SERVICE_UNAVAILABLE(7001)`。

- `RestClientException`
  - 包含 `401 / Unauthorized`，返回 `AI_API_KEY_INVALID(7004)`。
  - 包含 `429 / Too Many Requests`，返回 `AI_RATE_LIMIT_EXCEEDED(7005)`。
  - 其他调用失败返回 `AI_SERVICE_ERROR(7003)`。

而且项目统一用 HTTP 200 包业务错误码，前端通过 `success/code/message` 判断业务是否成功。

### 3.2 Spring AI 自动重试被关闭

配置里：

```yaml
spring:
  ai:
    retry:
      max-attempts: 1
      on-client-errors: false
```

这意味着底层 Spring AI 不做多次自动重试，失败会尽快抛出来，再由业务层决定是否重试或降级。这样做的好处是避免一个请求在模型服务异常时卡太久。

## 4. 结构化输出失败怎么办？

项目里有 `StructuredOutputInvoker`，专门处理“模型必须返回 JSON，但是返回格式不稳定”的场景，比如：

- JD 解析
- 面试题生成
- 简历分析
- 面试评估

流程是：

1. 第一次调用模型。
2. 用 `BeanOutputConverter` 解析模型输出。
3. 如果解析失败，先尝试本地修复 JSON 字符串里的未转义引号。
4. 如果仍失败，根据配置进入下一次重试。
5. 重试时会在 Prompt 里追加严格 JSON 指令，并带上上一次解析失败原因。
6. 达到最大次数后抛 `BusinessException`。

默认重试次数来自：

```yaml
app.ai.structured-max-attempts: 2
```

也就是说，它不是盲目重试，而是“带错误反馈的修复型重试”。

## 5. Redis Stream 异步任务失败怎么办？

异步任务统一继承 `AbstractStreamConsumer`，包括：

- 简历分析
- 知识库向量化
- 普通面试评估
- 语音面试评估

失败处理流程：

1. 消费者拉取 Redis Stream 消息。
2. 标记任务为 `PROCESSING`。
3. 执行业务逻辑。
4. 成功则标记 `COMPLETED` 并 ACK。
5. 失败则判断 `retryCount`。
6. 如果小于 `MAX_RETRY_COUNT = 3`，重新写入 Stream。
7. 如果超过 3 次，标记为 `FAILED`，保存错误信息。
8. 无论成功、失败或重新入队，当前消息都会 ACK，避免卡住消费组。

这里的关键点是：异步任务失败不会让用户请求一直等待，而是通过状态字段让前端轮询或查看失败原因。

## 6. 面试出题失败怎么降级？

`InterviewQuestionService` 做了比较明显的业务降级。

有简历时，它会并行生成两类题：

- 简历题，占 60% 左右。
- 方向题，占 40% 左右。

失败时降级策略：

- 简历题生成失败：降级为全方向题。
- 方向题生成失败：如果简历题已经成功，就只返回简历题。
- 两边都为空：回退到默认问题。
- 方向题返回空列表：回退到默认问题。

默认问题不是随机胡编，而是根据 Skill 分类生成类似：

```text
请谈谈你在“某个技术方向”的技术理解和实践经验。
```

这保证了模型偶发失败时，用户至少还能继续面试流程。

## 7. RAG 查询失败怎么处理？

RAG 链路有几层兜底。

### 7.1 Query Rewrite 失败

如果问题改写失败，不直接报错，而是使用原始问题继续向量检索。

### 7.2 向量检索过滤失败

pgvector 检索时，如果前置 filter expression 失败，会回退到：

1. 先不带过滤条件做向量检索。
2. 再在 Java 内存里按 `kb_id` 过滤。
3. 保留 `topK` 和 `minScore`，避免兜底路径召回太多弱相关内容。

### 7.3 没有命中资料

如果没有有效文档，返回固定兜底话术：

```text
抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。
```

### 7.4 流式输出失败

如果 SSE 流式回答中途失败，会通过 `onErrorResume` 返回：

```text
【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。
```

所以 RAG 不是直接把异常抛到前端，而是尽量给用户一个可理解的失败结果。

## 8. 语音面试中途失败怎么办？

语音面试的失败处理比较多，因为它涉及 WebSocket、ASR、LLM、TTS。

### 8.1 ASR 未就绪

如果 ASR 连接还没准备好，前端发来的音频块会直接丢弃，避免把异常音频送进识别服务。

### 8.2 ASR 断线

如果发送音频时发现 ASR 会话不存在或 append 失败：

1. 自动重启 DashScope ASR。
2. 短时间内重试发送当前音频块。
3. 如果仍失败，向前端发送错误信息：“语音识别连接中断，请刷新页面后重试”。

ASR 初始化阶段也有 ready 检查，最多自动重连 2 次。

### 8.3 LLM 失败

`DashscopeLlmService` 会捕获 LLM 异常，并把错误映射成用户可理解的话术：

- 认证失败：提示检查 API Key。
- timeout：提示 AI 服务响应超时。
- 429 / rate limit / quota：提示调用频率或额度超限。
- 网络连接失败：提示检查网络。
- 其他异常：返回 AI 服务暂时不可用。

语音 WebSocket 主流程也会捕获整轮异常，通过 WebSocket 给前端发送 `error` 消息。

### 8.4 TTS 失败

语音面试开启流式输出时，会边生成文本边按句子触发 TTS。

失败兜底：

- 某个句子的 TTS 超时或失败，不阻塞整个流程。
- 如果所有句子级 TTS 都失败，就用完整 AI 回复再做一次 TTS。
- 如果 TTS 完全没有音频，至少文本回复已经通过 WebSocket 发给前端。

### 8.5 用户长时间无操作

如果用户长时间不说话：

1. 先发送暂停警告。
2. 超时后把会话状态保存为暂停。
3. 关闭 WebSocket。
4. 停止 ASR，清理内存中的 session 状态。

这样可以避免一个断开的语音会话一直占用资源。

## 9. 文件和存储失败怎么处理？

文件链路主要通过 `BusinessException + ErrorCode` 处理：

- 文件太大：`BAD_REQUEST`
- 文件类型不支持：`RESUME_FILE_TYPE_NOT_SUPPORTED`
- 简历解析失败：`RESUME_PARSE_FAILED`
- 上传失败：`RESUME_UPLOAD_FAILED`
- 存储上传失败：`STORAGE_UPLOAD_FAILED`
- 存储下载失败：`STORAGE_DOWNLOAD_FAILED`
- 存储删除失败：`STORAGE_DELETE_FAILED`

知识库删除向量时有一个宽松处理：删除向量失败会记录日志，但不一定阻断其他删除操作。这属于“删除流程继续执行”的降级思路。

## 10. 项目中的错误类型

错误码按模块分域：

| 错误域 | 范围 | 例子 |
|---|---:|---|
| 通用错误 | 1xxx / HTTP 类语义 | 参数错误、未授权、资源不存在、系统异常 |
| 简历模块 | 2xxx | 简历不存在、解析失败、上传失败、重复上传、分析失败 |
| 面试模块 | 3xxx | 会话不存在、题目不存在、评估失败、题目生成失败 |
| 存储模块 | 4xxx | 上传、下载、删除失败 |
| 导出模块 | 5xxx | PDF 导出失败 |
| 知识库模块 | 6xxx | 知识库不存在、解析失败、查询失败、向量化失败 |
| AI 服务 | 7xxx | 服务不可用、超时、API Key 无效、频率超限 |
| 限流模块 | 8xxx | 请求过于频繁 |
| 面试日程 | 9xxx | 日程不存在 |
| 语音面试 | 10xxx | 语音会话不存在、语音评估失败、评估结果不存在 |
| Provider 管理 | 11xxx | Provider 不存在、配置读写失败、连通性测试失败 |

## 11. 面试时可以这样说

可以这样回答：

> 这个项目没有直接引入 Sentinel 或 Resilience4j 做标准熔断，但在关键链路上做了多层降级和失败恢复。入口层通过 `@RateLimit + Redis Lua` 做限流，避免高频请求压垮 AI 和数据库；AI 调用失败会被全局异常处理器统一映射成业务错误码，比如超时、API Key 无效、429 限额等；结构化输出如果 JSON 解析失败，会通过 `StructuredOutputInvoker` 带错误原因重试；Redis Stream 异步任务失败后会重新入队，最多重试 3 次，超过后把任务状态标记为 `FAILED` 并保存错误原因；业务层也有兜底，比如出题失败回退默认题、RAG 检索失败返回无命中或服务不可用提示、语音面试中 ASR 会自动重连，TTS 失败会回退到整段文本合成。因此它不是标准熔断框架方案，而是通过限流、重试、降级、状态持久化和友好错误返回保证中途失败时流程可恢复、用户可感知、后台可排查。

更短一点：

> 项目没有标准熔断器，但有工程化降级。同步请求走全局异常处理，异步任务走 Redis Stream 重试和 FAILED 状态，AI 结构化输出有解析失败重试，RAG 和出题有兜底结果，语音链路有 ASR 重连和 TTS fallback。核心目标是失败不拖垮主流程，错误可返回、状态可追踪、任务可重试。
