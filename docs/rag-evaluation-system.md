# RAG 评估体系设计说明

## 为什么要做

原来项目里的 RAG 质量主要靠人工样例验证，能够发现坏例，但不够工程化：一次 Prompt、切分策略、topK 或 rerank 参数调整之后，很难量化判断效果是变好还是变差。

因此项目新增一套 RAG Evaluation 接口，把一次问答链路拆成三类指标：检索指标、生成指标和工程指标。这样可以在调整文档解析、chunk 切分、向量检索、rerank 和提示词之后，用固定问题集持续回归。

## 评估入口

接口：`POST /api/knowledgebase/evaluation/rag`

请求示例：

```json
{
  "knowledgeBaseIds": [1],
  "question": "项目中 Redis Stream 为什么适合作为消息队列？",
  "expectedEvidence": [
    "Redis Stream 支持消费组、ACK、Pending List 和失败重试",
    "项目使用 AbstractStreamProducer 和 AbstractStreamConsumer 处理异步任务"
  ],
  "expectedAnswer": "Redis Stream 通过消费组、ACK 和重试机制保证异步任务可靠处理。",
  "topK": 5,
  "generateAnswer": true
}
```

## 指标设计

| 指标 | 衡量对象 | 项目实现 |
| --- | --- | --- |
| Hit Rate@K | 召回 | 标准证据是否出现在 topK 检索结果中 |
| MRR | 排序 | 第一条相关证据排在第几位 |
| Context Recall | 召回完整性 | 标准证据被找全的比例 |
| Context Precision | 上下文纯度 | topK 中真正相关 chunk 的比例 |
| Faithfulness | 生成忠实度 | 答案中的事实陈述是否能被上下文支撑 |
| Answer Relevancy | 回答相关性 | 答案是否回应问题，并和标准答案接近 |
| Citation Accuracy | 引用准确性 | `[1]` / `【1】` 引用是否指向有效上下文 |
| Latency / Cost | 工程指标 | 统计检索、rerank、生成耗时，并估算输入/输出 token |

## 当前实现方式

当前版本采用规则型评估，不依赖额外评审模型：

- 通过标准证据 `expectedEvidence` 作为 golden evidence；
- 用关键词覆盖、紧凑文本包含、F1 相似度判断 chunk 是否相关；
- 对答案按句子拆 claim，判断 claim 是否被上下文覆盖；
- 支持引用格式 `[1]` 和 `【1】`；
- 记录 `retrievalLatencyMs`、`rerankLatencyMs`、`generationLatencyMs`、候选 chunk 数、最终上下文数和 token 估算。

## 面试版说法

我没有只停留在“人工觉得回答不错”这种主观判断，而是给 RAG 链路补了一套评估体系。做法是把一次 RAG 问答拆成检索、生成和工程三层：检索层看 Hit Rate@K、MRR、Context Recall 和 Context Precision，判断证据有没有召回、排序靠不靠前、上下文是否干净；生成层看 Faithfulness、Answer Relevancy 和 Citation Accuracy，判断回答是否被上下文支撑、是否真正回答用户问题、引用是否准确；工程层记录检索、rerank、生成的耗时和 token 估算。这样每次调整 chunk 切分、topK、rerank 权重或 Prompt 后，都可以用固定评测集做回归，而不是靠感觉判断效果。

## 后续演进

后续可以把规则型 Faithfulness 和 Answer Relevancy 替换为 LLM-as-Judge，并把评测样例沉淀成数据集，结合定时任务做离线回归，输出版本间的指标变化。