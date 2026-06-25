# RAG 测评操作手册

以后你想自己测 RAG 效果，可以按这个流程走，不用每次都问别人。

## 1. 启动项目

确保 Docker 里的 PostgreSQL、Redis、RustFS 已经启动，后端也已经启动。

浏览器能打开下面接口说明后端是通的：

```text
http://127.0.0.1:8080/api/knowledgebase/list
```

或者用 PowerShell：

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/knowledgebase/list" | ConvertTo-Json -Depth 8
```

## 2. 找到要测的知识库 ID

调用：

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/knowledgebase/list" | ConvertTo-Json -Depth 8
```

看返回里的 `id`。

比如：

```text
id=7  消息队列常见面试题
id=8  分布式相关面试题汇总
```

## 3. 准备一条评测样例

一条评测样例要包含：

| 字段 | 作用 |
| --- | --- |
| `knowledgeBaseIds` | 要测哪些知识库 |
| `question` | 用户问题 |
| `expectedEvidence` | 标准证据，最好从原文/向量库 chunk 中摘取 |
| `expectedAnswer` | 标准答案，可选但建议写 |
| `answer` | 你要评测的答案；如果不传，可以让系统生成 |
| `topK` | 检索前 K 个上下文 |
| `generateAnswer` | 是否让系统调用大模型生成答案 |

标准证据最重要。不要只写“生产者、消费者、持久化”这种很短的词，最好写成原文片段，否则指标会不准。

## 4. 手动调用评测接口

复制下面命令，在 PowerShell 里改 4 个地方：

- `knowledgeBaseIds`
- `question`
- `expectedEvidence`
- `expectedAnswer` / `answer`

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$body = @{
  knowledgeBaseIds = @(7)
  question = '消息队列如何保证消息不丢失？'
  expectedEvidence = @(
    '存储消息阶段需要在消息刷盘之后再给生产者响应，否则机器突然断电可能导致消息丢失。',
    '消费者需要在执行完真正的业务逻辑之后再返回响应给 Broker。',
    '保证消息可靠性需要生产者、Broker、消费者三方配合。'
  )
  expectedAnswer = '消息队列要从生产者、Broker 和消费者三端保证可靠性：生产者处理 Broker 响应并失败重试；Broker 在刷盘或多副本同步后再响应；消费者在业务真正处理完成后再 ACK。'
  answer = '消息队列保证消息不丢失要从三段链路处理：生产者侧等待 Broker 响应，失败时重试或告警；Broker 侧在消息刷盘或多副本同步完成后再返回成功；消费者侧等业务逻辑真正执行完成后再 ACK。[1]'
  topK = 5
  generateAnswer = $false
} | ConvertTo-Json -Depth 8

Invoke-RestMethod `
  -Uri 'http://127.0.0.1:8080/api/knowledgebase/evaluation/rag' `
  -Method Post `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
  -TimeoutSec 60 | ConvertTo-Json -Depth 12
```

## 5. 如果想让系统自己生成答案

把 `answer` 去掉或设为空，把 `generateAnswer` 改成 `$true`：

```powershell
$body = @{
  knowledgeBaseIds = @(7)
  question = '消息队列如何保证消息不丢失？'
  expectedEvidence = @(
    '存储消息阶段需要在消息刷盘之后再给生产者响应。',
    '消费者需要在执行完真正的业务逻辑之后再返回响应给 Broker。'
  )
  expectedAnswer = '消息队列要从生产者、Broker 和消费者三端保证可靠性。'
  topK = 5
  generateAnswer = $true
} | ConvertTo-Json -Depth 8
```

注意：`generateAnswer = $true` 会调用大模型，需要 API Key 正常。

## 6. 指标怎么看

| 指标 | 怎么判断 |
| --- | --- |
| `Hit Rate@K = 1` | topK 里找到了正确证据 |
| `Hit Rate@K = 0` | 正确证据没召回，优先查向量化、query rewrite、topK、chunk 切分 |
| `MRR` 低 | 正确证据排得靠后，重点优化 rerank |
| `Context Recall` 低 | 标准证据没找全，可能 topK 太小或 chunk 切太碎 |
| `Context Precision` 低 | topK 噪声多，可能 chunk 太大、rerank 不准 |
| `Faithfulness` 低 | 答案没有被上下文支撑，可能模型幻觉或 Prompt 约束不够 |
| `Answer Relevancy` 低 | 答案偏题，或没有覆盖问题核心关键词 |
| `Citation Accuracy` 低 | 答案里的 `[1]`、`【1】` 引用没有指向真正相关证据 |
| `retrievalLatencyMs` 高 | 向量检索慢 |
| `rerankLatencyMs` 高 | 重排慢 |
| `generationLatencyMs` 高 | 大模型生成慢 |

## 7. 推荐你的测评流程

不要只测一条，建议准备 10 到 20 条固定问题，分成几类：

1. 能在知识库中直接找到答案的问题。
2. 需要跨多个 chunk 综合的问题。
3. 容易召回相似但错误内容的问题。
4. 知识库没有答案的问题，用来测兜底策略。
5. 表格、图片、PDF、Word、Excel 上传后的问题，用来测多模态解析和 chunk 策略。

每次你改下面这些内容，都重新跑一遍同一批问题：

- chunk 切分策略
- topK
- minScore
- query rewrite
- rerank 权重
- Prompt
- 文档解析方式

然后比较指标变化。

## 8. 面试时怎么说

我会维护一批固定 RAG 评测样例，每条样例包含用户问题、标准证据和可选标准答案。每次调整文档解析、chunk 切分、topK、query rewrite、rerank 或 Prompt 后，我都会调用 RAG Evaluation 接口做回归。评测结果会拆成检索、生成和工程三层：检索层看 Hit Rate@K、MRR、Context Recall、Context Precision；生成层看 Faithfulness、Answer Relevancy、Citation Accuracy；工程层看检索、重排、生成耗时和 token 估算。这样可以定位问题到底是没召回、排序差、上下文噪声大、模型幻觉，还是引用不准确。