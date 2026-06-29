

# RAG 向量索引算法和向量数据库

RAG解决的是模型不知道新知识或私有知识的问题，微调更适合拒绝模型不会按照你的方式说话或做事的问题

知识变动频繁、需要引用来源，优先RAG;输出风格和任务行为不稳定，考虑微调;既要懂领域表达又要查实时数据，可以两者结合

长上下文适合少量材料的深度分析，但企业级海量知识库、权限隔离和成本控制，仍需要RAG

## RAG的核心优势和局限性

知识更新成本低；减少幻觉，方便追随来源；数据隔离也更容易做（在检索层实现多租户隔离和访问控制）；换领域的成本也低
检索质量决定上限，上下文不是越长越好，延迟，工程复杂度，token成本

工程上一般分为离线索引和在线检索生成两个阶段，离线阶段做文档清洗、切分、Embedding向量化和入库；在线阶段对用户问题向量化，从向量库或搜索引擎中召回相关Chunk,再经过重排、上下文构建、最后由大模型生成答案

## RAG Chunking策略

固定长度切分
递归字符切分
语义切分：按意义分，但有代价（用Embedding模型判断句子之间的语义相似度）
按文档结构切分
Parent-Child CHunk 召回和上下文的折中

重叠控制：边界问题 通用文本用512 Token的块大小加50-100Token 的重叠

语义丢失
结构截断、上下文蒸发、表格结构破坏、专有名词变形
应对策略：增加语义入口（给每个Chunk生成摘要和问题变体一起入索引）；保留层次元数据

离线阶段
文件上传-格式校验-Layout解析-清洗去噪-Chunking切分-Metadata保存-向量化并入库

格式校验：文件大小、文件拓展名、MIME类型、是否空文件、是否损坏

解析校验：是否提取出文件、内容长度是否合理、乱码率是否过高、结构是否完整

Chunking校验：总Chunk数、平均Chunk大小、是否有过大小块

## 多模态内容怎么处理

图片
Multi-Vector Retriever 
摘要用于检索、原图用于生成
向量库里面存图片摘要，原始图片存docstore。检索时先命中摘要，再根据doc_id找到原图，最后一起给多模态LLM

表格
转成Markdown表格
转成结构化json

图表
提取完整图表元信息
生成有信息量的caption
识别图表和上下文的关系

pdf Layout-aware-parser
RAG 文档处理中的Metadata有什么作用（用来保存文档来源、页码、章节路径、版本、权限标签等信息）
不仅能溯源，还能做权限过滤、版本控制、检索过滤和上下文补全

从零搭建文档处理管线
先跑文本类文档-->在攻坚pdf（少量样本实验）-->再处理图片和表格-->建立质量闭环（能否正确召回Chunk、召回内容是否完整）

## RAG向量索引算法和向量数据库

解决问题:RAG系统中存几十万条Chunk,如果直接存MySQL Embedding，查询时全表遍历计算相似度，延迟到秒级，生产环境不可接受。真正上线时，需要考虑向量数据库和ANN向量索引算法。

离线阶段把文档Chunk转成文档向量写入向量数据库，在线阶段把用户问题转成查询向量，再检索最相似的TopK文档向量。选用向量数据库本质上是在大规格高维向量力低延迟找到最相关的topK

### ANN近似最近邻

思想：不保证100%找到绝对最近的向量，而是用很高概率找到足够相似的向量，用少量召回损失换取更低延迟

### 向量相似度

欧氏距离L2 <->    内积<#>       余弦距离<=>

最常用的是余弦距离，因为文本语义更关注是否接近，而不是向量长度本身

### 向量索引算法

| 算法    | 原理                | 优点                   | 缺点                           | 适合场景                 |
| ------- | ------------------- | ---------------------- | ------------------------------ | ------------------------ |
| Flat    | 遍历所有向量        | 100% 准确              | 数据大时很慢                   | 小规模、离线评测         |
| HNSW    | 分层小世界图        | 查询快、召回高         | 内存消耗大、构建慢             | 中大规模、高召回、低延迟 |
| IVFFLAT | 聚类 + 倒排桶       | 内存更友好、构建较快   | 召回略低，需要调参             | 内存敏感、可接受召回损失 |
| IVF-PQ  | 聚类 + 乘积量化压缩 | 支持海量数据、节省内存 | 有精度损失                     | 超大规模、内存敏感       |
| LSH     | 哈希分桶            | 思路简单               | 高维语义检索中综合表现不一定好 | 特定近似检索场景         |

####  HNSW

将向量组织成多层图结构，查询时先在上层快速跳转，定位到大概区域，再到底层做更精细的尽力搜索。

层次化构建，贪心搜索，由粗到精

常见三个参数：m（每个节点最大连接数）；ef_construction（构建索引时的搜索范围）；ef_serach(查询时的搜索范围)
默认参数：m:16 ef_construction:64 ef_search:40（搜索候选范围越大，召回率通常越高，但延迟和CPU开销也会增加，一般通过业务测评调）

#### IVFFLAT

倒排聚类索引，先用K-means把向量空间分成多个桶，查询是先找到最近的几个桶，再到桶里面做暴力搜索

内存占用低，结构简单，构建通常快，适合内存和构建成本敏感的场景

它的优点是内存更友好、构建通常更快，缺点是召回率受 lists 和 probes 参数影响较大，数据分布变化时可能需要重新训练或重建索引。

## 元数据过滤为什么会影响向量检索？

ANN 索引通常可能先按向量距离找候选，再应用过滤条件。如果过滤条件很严格，最终结果可能少于 Top-K，甚至某些查询形态下退化成更慢的扫描。

### 处理方法：

1. 增大 ef_search 或 LIMIT，让更多候选进入过滤阶段
2. 预过滤：先按 metadata 过滤，再做向量搜索
3. 部分索引：给常见过滤条件单独建索引
4. 使用 pgvector 0.8.0+ 的 iterative index scans

## 向量数据库

#### PostgreSQL+pgvector（适合项目或中小期项目，尤其是业务数据和向量数据需要强一致性，能在同一个事务里管理时优势明显） MongoDB

技术栈统一，不需要额外引入一套向量数据库，向量数据和业务数据可以同事务管理，团队已有SQL经验可以复用，方便结合Where过滤条件，但过滤条件可能影响向量索引命中，需要检查执行计划

项目需要同时存结构化数据，比如简历、面试记录，也要存向量数据

#### 原生专业向量数据 Milvus,Qdrant 当向量规模达到亿级甚至更高，或者对 QPS 和延迟要求很苛刻时，原生向量数据库通常比 pgvector 更合适，但代价是多一套运维、监控、备份和学习成本。  

### 为什么选择PostgreSQL+pgvector

当前业务规模不大，向量数量在百万级以内，同时系统本来就需要存储结构化业务数据，比如用户、文档、面试记录、权限信息。使用 PostgreSQL + pgvector 可以减少组件数量，降低运维复杂度，还能保证业务数据和向量数据在同一个事务里管理。配合 HNSW 索引，当前检索性能和召回率可以满足需求。后续如果数据规模和 QPS 上升，再考虑拆出 Milvus、Qdrant 或 Elasticsearch/OpenSearch。

## 向量数据库选择

| 场景                                    | 推荐方案                                                    |
| --------------------------------------- | ----------------------------------------------------------- |
| 小于 100 万向量，团队已有 PostgreSQL    | PostgreSQL + pgvector                                       |
| 小于 100 万向量，团队已有 ES/OpenSearch | 复用 ES/OpenSearch 向量检索                                 |
| 百万到十亿级，需要专业向量能力          | Milvus、Qdrant、Weaviate                                    |
| 不想自运维                              | Pinecone、Zilliz Cloud、Weaviate Cloud                      |
| 强依赖混合检索                          | ES/OpenSearch、Weaviate，或 PostgreSQL + pgvector + pg_bm25 |

## pgvector实践里要注意什么

索引类型和查询操作符必须一致

IVFFLAT要先有数据再建索引

要用EXPLAIN ANALYZE看执行计划。不会只看 SQL 能不能跑，而是会通过 EXPLAIN ANALYZE 检查是否走了 Index Scan，并结合召回率、P95/P99 延迟和最终答案质量做评估。

大量删除和更新后要维护索引

# RAG知识库文档更新策略

不能只做简单的重新入库，本质上是一个简单的数据同步和索引一致性问题

## Embedding模型必须保持一致

索引时使用的Embedding模型，必须和查询时使用的Embedding模型一致。模型升级不能只改查询侧，而要对历史文档重新编码、重建索引，最好通过双索引灰度和别名切换降低风险。

## 元数据切换:是增量更新、版本控制、权限过滤和回滚的基础

```json
{
  "doc_id": "doc-uuid-001",
  "chunk_id": "chunk-uuid-001",
  "content_hash": "sha256:abc123...",
  "version_id": 3,
  "chunk_strategy": "semantic",
  "chunk_size": 512,
  "chunk_overlap": 50,
  "source_id": "confluence-page-123",
  "source_type": "confluence",
  "title": "订单中心接口文档",
  "section_path": "技术文档 / 订单系统 / 接口规范",
  "page": 5,
  "tenant_id": "tenant-001",
  "acl": ["role:admin", "team:order-team"],
  "created_at": "2025-03-01T10:00:00Z",
  "updated_at": "2025-04-15T14:30:00Z",
  "embedding_model": "text-embedding-3-large",
  "embedding_model_version": "2025-01-15",
  "embedding_dimension": 3072,
  "is_deleted": false
}
```

|       字段        |       作用       |
| :---------------: | :--------------: |
|     `doc_id`      |   标识原始文档   |
|    `chunk_id`     |  标识具体 Chunk  |
|  `content_hash`   | 判断内容是否变化 |
|   `version_id`    |   记录文档版本   |
|   `is_deleted`    |    软删除标记    |
|    `tenant_id`    |    多租户隔离    |
|       `acl`       |     权限控制     |
| `embedding_model` |   记录向量模型   |
| `chunk_strategy`  |   记录切分策略   |

## 增删改查

新增文档必须保证幂等性：doc_id + content_hash简历唯一约束

修改文档：根据doc_id找到旧版本chunk-->标记为  is_deleted=true -->重新解析新文档--> 重新切分Chunk-->重新计算hash 和 embedding --> 写入新版本 Chunk

删除文档：软删除+延迟物理删除+删除审计日志                       权限变更后的幽灵数据：将原来所有员工可见变成仅管理员可见，权限变更后要触发重新索引，至少原子更新向量库中的ACL元数据

文档删除仍被召回：可能只删了向量库，没删全文索引；删除操作要保证向量库、元数据库、全文索引三点一致

## 增量更新和全量重建

### 增量更新  

源系统推送变更事件、监听数据库binlog或变更日志、定时轮询

更稳的组合：事件驱动+轮询兜底+消息队列解耦（源系统产生变更事件后，投送到Kafka，RAG更新Worker消费事件并更新索引，同时用定时轮询兜底）

### 全量重建

Embedding模型升级和Chunk策略调整都应该出发全量重建

索引别名切换：通过索引别名把流量从旧索引切到新索引，保留旧索引用于回滚

实时增量+定期全量重建+事件驱动的紧急重建

## 如何保证更新链路可靠？

### 幂等更新

不会重复写入向量、不会生成多份Chunk

doc_id + content_hash 唯一约束
doc_id + version_id 唯一约束
乐观锁 / 分布式锁
事务 outbox

让数据库唯一约束兜底，而不是应用层先查再写

### 乱序事件处理

事件携带 source_version / revision / updated_at
写入前校验 event.version >= current_version
旧事件直接丢弃或记录审计日志
Kafka 按 doc_id 作为 key，保证同一文档进入同一 partition
监控乱序丢弃数量

### 失败重试和死信队列

指数退避重试，进入死信队列

### 回滚机制

### 灰度发布

# GraphRAG

普通向量 RAG 主要检索文本 Chunk，适合局部事实问答；GraphRAG 会把文档中的实体、关系和主题结构显式建模成知识图谱，查询时不仅可以按语义找片段，还可以沿着图关系做多跳检索，或者利用社区摘要回答全局问题。它的优势是关系推理、全局归纳和可解释性更好，代价是构建成本、实体消歧、关系抽取、增量更新和权限控制都更复杂。

如果问题主要是简单文档问答，或者数据量小，关系不复杂，向量RAG加混合检索和rerank往往划算，GraphRAG应该用在向量RAG的坏例明确指向多跳关系、跨文档归纳和结构化约束的场景。

Chunk是信息孤岛：语义相似不等于关系完整

向量相似度不擅长多跳处理

全局性问题很难靠Topk片段回答

## 构建阶段:

文档解析---文本切分---实体抽取--关系抽取---图谱归一----社区发现-----摘要生成----索引入库

## GraphRAG 适合什么场景

企业知识库复杂问答        IT 架构和故障影响分析            

金融、风控、合规、供应链        跨文档主题归纳

##  GraphRAG 不适合什么场景

 数据量小、问题简单           文档质量太差           实时性要求极高

## 面试中怎么回答 GraphRAG？

GraphRAG 是在传统 RAG 基础上引入知识图谱的一种检索增强方案。普通向量 RAG 主要检索文本 Chunk，适合局部事实问答；GraphRAG 会从文档中抽取实体、关系和关键声明，构建知识图谱，并通过图遍历、路径扩展、社区发现和社区摘要来增强检索。

它的优势是可以处理多跳关系问答、影响分析、归因分析、跨文档主题归纳等问题。例如普通 RAG 只能召回和“支付故障”相似的文本片段，而 GraphRAG 可以沿着“订单中心 → 支付网关 → 风控服务 → 交易超时”这样的关系链路收集证据。

但 GraphRAG 不是普通 RAG 的默认升级方案。它的构建和维护成本更高，需要处理实体抽取、关系抽取、实体消歧、图谱归一、社区摘要、增量更新、权限过滤和评测闭环。项目中应该先做好向量 RAG 基线，收集 badcase，只有当问题集中在关系推理和全局归纳上时，再引入轻量图谱或 GraphRAG。



#  RAG 优化：从召回、重排到上下文工程的系统调优

评估策略

|       指标        |  衡量对象  |                 说明                  |
| :---------------: | :--------: | :-----------------------------------: |
|    Hit Rate@K     |    召回    |    正确证据是否出现在前 K 个结果里    |
|        MRR        |    排序    |      第一个正确证据排得有多靠前       |
|  Context Recall   | 召回完整性 |        回答所需证据是否被找全         |
| Context Precision | 上下文纯度 |   放入上下文的内容有多少是真的相关    |
|   Faithfulness    | 生成忠实度 |        答案是否能被上下文支撑         |
| Answer Relevancy  | 回答相关性 |       答案是否真正回应用户问题        |
| Citation Accuracy | 引用准确性 |       引用位置是否支撑对应结论        |
|  Latency / Cost   |  工程指标  | P95 延迟、Token、重排耗时、缓存命中率 |

## 先做数据治理

很多 RAG 系统失败，不是因为检索不准，而是被检索的数据一开始就是错的。

## Metadata 不是展示字段，而是检索硬约束

Metadata 不只是辅助信息，而是生产级 RAG 的检索约束和证据链。它可以支持权限过滤、租户隔离、版本过滤、时间过滤、来源引用和结果去重。如果没有 Metadata，RAG 很难做到可控、可追溯和不越权。

## Chunk 策略：别把知识切碎了

Chunk 太小会丢上下文，Chunk 太大会引入噪声。

这些只能作为起点，真正应该用评估集比较不同 Chunk 参数下的 Context Recall、Context Precision、答案正确率和平均上下文 Token。

### 语义切分

语义切分不是机械按 Token 数切，而是根据标题、段落、句子相似度或语义边界来切。

### Parent-Child Chunk

Child Chunk：300 Token，用来做向量检索
Parent Chunk：1200 Token，用来提供完整上下文

### 给 Chunk 增加语义入口

## 召回优化：不要只靠向量相似度

向量检索擅长语义相似，BM25 擅长精确词匹配，两者是互补关系。但像“错误码 E1027”“ABX-4421 型号参数”“v3.2 价格政策”这类包含错误码、SKU、版本号的问题，BM25 或关键词检索非常重要。

**Hybrid Search 混合检索**：向量检索：召回语义相似候选；BM25 / 稀疏向量：召回关键词候选；RRF 或分数归一化：融合两路结果；去重；进入 Rerank

RRF 的好处是不用强行比较 BM25 分数和向量余弦分数，**而是按排名位置融合**，调参压力更小。

## Query Rewrite：先让问题变得可检索

Query Rewrite 必须保留原始问题，不要只用改写后的查询。因为改写模型可能理解错用户意图，原始 query + 改写 query 一起召回，再融合结果

## TopK

Top-K 不是越大越好。生产中应该区分粗召回 Top-K、重排 Top-N 和最终上下文 Top-N。粗召回要保证召回完整性，最终上下文要保证高信噪比。

## Rerank：把“相关”重新排成“可回答”

Rerank 的价值就是把真正能回答问题的片段排到前面。

Metadata 预过滤
  ↓
Hybrid Search 粗召回 30 到 100 条
  ↓
去重和相邻片段合并
  ↓
Rerank 选出 5 到 10 条
  ↓
上下文压缩后放入 Prompt

不要一上来就加 Reranker。应该先看 Context Recall，也就是正确证据有没有进入粗召回候选池。网页明确说，很多人直接上 Reranker 没效果，根因是粗召回阶段根本没把正确文档找出来。

###  Rerank 怎么选？

|          方案          |          优点          |             缺点             |           适用场景           |
| :--------------------: | :--------------------: | :--------------------------: | :--------------------------: |
| Cross-Encoder Reranker | 相关性判断细，成本可控 | 需要选模型，有语言和领域偏差 |         通用生产链路         |
|        LLM 打分        |  可解释性强，规则灵活  | 慢、贵、稳定性受 Prompt 影响 |   小流量、高价值、复杂判断   |
|        规则重排        |       便宜、可控       |       只能处理明确规则       | 时间、权限、版本、来源优先级 |
|        混合重排        |          灵活          |           工程复杂           |    企业知识库、客服、合规    |

默认用专用Reranker做主链路，用规则补业务约束，用LLM打分做离线评估或高价值兜底。

## 上下文工程：别把模型当垃圾桶

上下文压缩

上下文排序

Prompt 要限制证据边界

## 评估：不做评估，优化就是玄学

Context Recall Context Precision   Faithfulness    Answer Relevancy   延迟 成本

## 一套可落地的排查路径

### 第一步：把失败样本分类

完全没召回正确文档
召回了正确文档，但排名靠后
正确文档进入上下文，但答案没用上
答案用了上下文，但理解错了
引用了不存在或不相关来源
应该拒答却强行回答
权限、时间、版本过滤错误

### 第二步：看正确证据有没有进入候选池

文档是否入库
文档解析是否正确
Chunk 是否切断关键事实
Metadata 过滤是否过严
Query 是否需要改写、分解或 HyDE
是否需要 BM25 或 Hybrid Search

### 第三步：正确证据在候选池里，但没进上下文

Rerank 模型是否适配语言和领域
Rerank 输入是否过长被截断
分数融合是否把关键词结果压下去
相邻 Chunk 合并是否带入噪声
rerank_top_n 是否过小

### 第四步：上下文正确，但答案错误

Prompt 是否要求基于上下文回答
上下文是否有互相冲突的版本
证据是否在上下文中间位置被淹没
问题是否需要多跳推理或对比表
是否需要结构化输出和引用约束
是否需要先压缩再生成

### 建立回归测试

## 生产调优优先级

1. 先做数据治理：文档解析、去噪、标题层级、页码、表格、Metadata
2. 建立最小评估集：先用 50 条真实问题跑通回放流程
3. 调 Chunk 策略：比较固定长度、结构化切分、Parent-Child、语义切分
4. 引入 Hybrid Search：向量负责语义，BM25 / 稀疏向量负责精确词
5. 加入 Query Rewrite：处理口语化、缩写、多意图、多跳问题
6. 加 Rerank：粗召回扩大候选池，重排后保留高质量证据
7. 做上下文压缩：去重、裁剪、摘要、结构化抽取，控制 Token 和噪声
8. 完善生成约束：证据不足就拒答，关键结论带引用
9. 灰度和监控：按版本记录指标，持续收集失败样本

闭避免上下文污染：RAG噪声污染、权限污染、Memory错误固化、新旧事实冲突、Prompt注入污染

Context Recall Context Precision Faithfulness  Answer Relevancy 
Tool Success Rate Format Valid Rate 

# AI系统设计

## Prompt Demo到生产系统最大的差距是什么

工程治理。demo关注模型能不能答，生产系统关注稳定性、权限隔离、成本控制、可观测和数据合规

## 为什么需要模型网关

模型网关把供应商差异、模型路由、fallback、限流、熔断、Token预算、成本统计和观测统一起来，，避免业务代码直接耦合某个模型API

##  **同步、流式、异步怎么选**

短小任务同步；长答案和聊天走流式，报告生成、批量处理、多工具任务走异步，判断时重点看任务耗时、TTFT、重试和恢复    

## **Prompt 为什么要做版本管理**

会直接影响输出质量、工具调用、检索策略和成本。版本管理可以支持灰度、回滚、审计

##  **Tool Calling 的安全边界在哪里**

模型提出工具调用意图，参数校验、权限校验、敏感操作确认和审计必须有后端系统完成

##  **LLM-as-Judge 能不能替代人工评测**

适合自动化回归、线上抽样和大规模初筛，但关键任务仍需要规则校验、人工复核和用户反馈闭环

## 怎么设计一个生产级 AI 应用

我会先区分 Demo 和生产系统的差距。
一个简单 Demo 只是前端传问题，后端拼 Prompt 调模型 API。
但生产级 AI 应用需要解决稳定性、权限、成本、可观测、评测和数据治理问题。

架构上我会分几层：
入口层负责鉴权、限流、脱敏、幂等；
业务编排层判断请求走普通问答、RAG、Agent、多工具任务，选择同步、流式或异步；
Prompt 和 Context 层负责 Prompt 版本管理、变量校验、上下文组装和 Token 预算；
RAG、Memory、Tool 分开治理，RAG 管共享知识，Memory 管用户长期偏好，Tool 管真实业务动作；
模型网关负责多模型路由、fallback、限流熔断、Token 统计和成本归因；
最后通过 Trace、评测集、线上失败样本回放形成持续优化闭环。

另外，Tool Calling 不能让模型直接执行高风险动作，模型只能提出调用意图，后端必须做参数校验、权限校验、二次确认和审计。

# 大模型网关

LLM Gateway = API 网关能力 + 大模型调用控制面

##  LLM Gateway 和 LLM Router 有什么区别

LLM Router 只负责一件事：选模型

意图分类 -> 小模型
代码生成 -> 强模型
法律审核 -> 合规模型
普通摘要 -> 便宜模型

LLM Gateway 管的是整个模型调用生命周期

鉴权   限流  路由    Fallback     重试   Token预算  成本统计  日志审计

## 为什么不能所有请求都用最强模型

最贵的模型不一定是最适合的模型。

第一，成本不可控。单次看起来不贵，但流量上来之后会非常烧钱。
 第二，延迟不稳定。强推理模型不一定适合实时交互、语音对话、轻量判断。
 第三，资源浪费。简单任务没有必要占用强模型，复杂任务也不一定因为用强模型就变好，很多时候问题出在上下文组织和 Prompt 设计。

能不用模型，就不用模型
能用小模型，就用小模型
复杂、高风险任务，再用强模型

## 限流不能只按 QPS，要按 Token 限流

调用前先估算输入 Token 和预留输出 Token；

从用户桶、租户桶、模型桶、供应商桶中预扣预算；

模型调用结束后，拿供应商返回的真实 usage；

根据真实 usage 对账修正。

## 成本统计：要知道钱花在哪里

哪个租户成本最高？
哪个功能最烧 Token？
哪个 Prompt 版本导致输出变长？
哪个模型在某个场景下性价比最好？
Fallback 主要发生在哪个供应商？
模型升级后，成本和质量有没有变化？

## 观测与审计：不要只记录最终答案

```json
{
  "request_id": "req_202605210001",
  "attempt_id": "att_01",
  "tenant_id": "team_java",
  "user_id": "u_1024",
  "scene": "knowledge_qa",
  "prompt_version": "rag_qa_v7",
  "provider": "openai",
  "model_tier": "tier-balanced",
  "model": "provider-model-id",
  "route_reason": "scene=knowledge_qa,cost_priority=true",
  "input_tokens": 4210,
  "output_tokens": 612,
  "cost": 0.0059,
  "ttft_ms": 680,
  "latency_ms": 4120,
  "fallback_used": false,
  "finish_reason": "stop"
}
```

元数据长期保留，Prompt 和响应正文**采样存储**，PII 在入口脱敏，按租户配置是否保存原文，并提供导出和删除能力

Prompt 里可能包含用户隐私、企业文档、内部代码、合同条款。

## 一次请求进入 Gateway 后怎么执行

鉴权与租户识别

判断任务场景

渲染Prompt

估算Token预算

选择模型和供应商

执行限流和预算扣减

通过Provider Adapter调用模型

解析响应，json、文本、tool call、usage

失败按错误类型Fallback

记录usage、trace、成本、延迟、错误

返回统一业务结果

## Fallback 怎么设计

Fallback 不能简单理解为失败就换模型。
要先区分错误类型：网络抖动、供应商 5xx、429 限流可以重试或切模型；
上下文超限应该压缩上下文或换长上下文模型；
参数错误不能重试；
高风险任务不能偷偷降级到低质量模型。

**同时 Fallback 要和幂等键绑定，避免一次用户请求触发多次模型调用、多次扣费或重复落库。**
每次 fallback 也要记录 attempt_id、fallback_used、error_code 和 route_reason，方便后续复盘。

## 路由策略怎么演进

没有 trace，不上分类器；
没有评测集，不上学习型 Router；
没有成本上限，不上 Agentic 路由。

## 面试版回答

大模型网关不是简单转发请求，而是模型调用的统一治理入口。

在生产环境中，业务服务不应该直接散落调用不同供应商SDK。

否则会出现模型名写死、API KEY分散、限流不好处理、成本不可见、日志难回放等问题。

LLM Gateway 会统一封装模型请求，对上提供接口，对下通过Provider Adapter适配OpenAI等不同供应商。

核心功能包括模型路由、Fallback、Token预算、多维限流、成本归因、日志审计和质量回放

模型路由只是 Gateway 的一部分。Router 负责选模型，Gateway 负责整次调用生命周期治理。
第一版我不会直接上复杂学习型 Router，而是先做统一接入、usage 记录、规则路由、Fallback 和 Token 预算。
等有了 trace、评测集和线上反馈后，再考虑语义路由、级联路由或学习型路由。

# AI 语音技术详解：从 ASR、TTS 到实时语音 Agent 的工程化落地

## 打断处理不是暂停按钮

前端通过isAiSpeakingRef标记AI是否在说话，说话时停发音频。后端收到control消息取消生成。

## 噪声环境比测试环境复杂太多

interview-guide 前端通过 `getUserMedia` 开了 3 个常见音频前处理选项

```vue
const stream = await navigator.mediaDevices.getUserMedia({
  audio: {
    echoCancellation: true, // AEC：消除扬声器回声
    noiseSuppression: true, // NS：压低背景噪声
    autoGainControl: true, // AGC：自动增益，让音量更稳定
    sampleRate: 16000,
  },
});
```

## 上下文不只是文字历史

interview-guide 用 WebSocket 消息类型区分了不同状态：

```vue
// voiceInterview.ts
export interface WebSocketSubtitleMessage {
  type: "subtitle";
  text: string;
  isFinal: boolean; // true 表示用户已确认提交
}

export interface WebSocketAudioResponseMessage {
  type: "audio";
  data: string; // Base64 音频
  text: string; // 对应的文字
}

export interface WebSocketControlMessage {
  type: "control";
  action: string; // 'submit' | 'cancel' | 'pause'
  data?: Record<string, unknown>;
}
```

前端根据 `isFinal` 判断用户是否真的说完了，避免把用户中途停顿当成确认

## 回声导致的误打断

**AI 播放的声音被麦克风采集后，VAD 或 ASR 会误判为用户说话，导致 AI 自我打断。**

```vue
if (isAiSpeakingRef.current) {
  return; // AI 说话时停发音频
}
```

这种“静默丢弃”的方案确实避免了自我打断，但代价是**用户在 AI 说话期间的真正打断也被屏蔽了**

更精细的方案

AI说话是继续接受音频，但不发到ASR

在AEC处理后的音频上运行端侧VAD，而非原始麦克风音频

结合回声参考、连续帧能量、VAD置信度和播放队列状态判断是不是用户真的在插话

## 整体架构

前端没有等 VAD 判断“用户说完”后才发送音频，而是持续分块发送；VAD 只是告诉后端“这一段可能结束了”。这样可以减少额外等待。项目里音频通过 AudioWorklet 重采样成 16kHz、Int16 PCM，并按 200ms 一块发送给后端 ASR。

后端通过 WebSocket 管理会话生命周期，接收客户端音频后转发给 ASR，接收 `submit`、`cancel`、`pause` 等控制消息后触发对应动作。ASR 会话 ready 后才通知前端允许录音，避免“前端已经发音频，但 ASR 连接还没准备好”的问题。

TTS 部分的优化重点是：**LLM 每输出一个完整句子，就提交给 TTS 队列合成，前端收到音频分片后立即入队播放**。这样不需要等整段回答全部生成和合成完，首段语音可以更快播放。

前端 React
  - getUserMedia 采集音频
  - AudioWorklet 分块处理音频
  - VAD 检测用户说话
  - WebSocket 发送音频和控制消息
  - AudioContext / HTMLAudioElement 播放音频

后端 Spring Boot
  - WebSocketHandler 管理会话
  - ASR 服务负责实时转写
  - LLM 服务负责生成面试回复
  - TTS 服务负责合成音频
  - 会话状态管理 pause / resume / end

### [前端：音频采集与 VAD](https://javaguide.cn/ai/system-design/ai-voice.html#前端-音频采集与-vad)

前端的核心是 `AudioRecorder` 组件。它做了这么几件事：

**第一步，获取麦克风权限并配置音频参数：**

```typescript
const stream = await navigator.mediaDevices.getUserMedia({
  audio: {
    echoCancellation: true,
    noiseSuppression: true,
    autoGainControl: true,
    sampleRate: 16000, // ASR 需要 16 kHz
  },
});
```

**第二步，初始化端侧 VAD：**

```typescript
const vadInstance = await window.vad.MicVAD.new({
  getStream: async () => stream,
  onSpeechStart: () => {
    onSpeechStart?.(); // 触发回调
  },
  onSpeechEnd: () => {
    onSpeechEnd?.();
  },
});
await vadInstance.start();
```

**第三步，使用 AudioWorklet 做音频分块采集：**

VAD 的 `onSpeechEnd` 只是告诉你用户可能说完了，真正的音频还是要分块发送给服务端。interview-guide 的实现是：

```typescript
await audioContext.audioWorklet.addModule("/audio-worklet/pcm-processor.js");

const workletNode = new AudioWorkletNode(audioContext, "pcm-processor");
workletNode.port.onmessage = (event) => {
  if (!recordingActiveRef.current) {
    return;
  }
  const base64 = arrayBufferToBase64(event.data as ArrayBuffer);
  onAudioData(base64); // 200 ms Int16 PCM，发送给后端 ASR
};

source.connect(workletNode);
workletNode.connect(gainNode);
gainNode.connect(audioContext.destination);
```

`pcm-processor.js` 运行在音频渲染线程中，负责把浏览器输入的 Float32 音频重采样成 16 kHz、Int16 PCM，并按 200 ms 一块通过 `postMessage` 交回主线程。相比已经废弃的 `ScriptProcessorNode`，`AudioWorkletNode` 不会把音频处理压在 UI 主线程上，延迟和卡顿风险更低。

#### **为什么不等 VAD 触发 `onSpeechEnd` 再发音频？**

因为 VAD 检测有延迟，等它确认用户说完了再开始发音频，会多等一段静音确认时间。更合理的做法是持续分块发送，VAD 触发 `onSpeechEnd` 只是告诉后端“这一段可能结束了，可以准备提交给 LLM”。

不过，interview-guide 的语音面试没有采用“检测到静音就自动提交”。它的做法是**ASR 持续转写、用户手动点击提交**。这样可以避免候选人中途停顿时被系统抢答，也能解决“后面的话覆盖前面的回答”的体验问题：前端只把 ASR 结果作为回答草稿，进入下一轮面试由 `submit` 控制消息决定。

### [后端：WebSocket 会话管理](https://javaguide.cn/ai/system-design/ai-voice.html#后端-websocket-会话管理)

```java
// VoiceInterviewWebSocketHandler.java
public class VoiceInterviewWebSocketHandler {
    // 会话状态：idle -> listening -> thinking -> speaking -> completed
    // 支持：pause（暂停）、resume（恢复）、end（结束）

    // 收到客户端音频
    public void handleAudioMessage(String sessionId, String audioBase64) {
        asrService.sendAudio(sessionId, decodeBase64(audioBase64));
    }

    // 收到客户端控制消息
    public void handleControlMessage(String sessionId, String action, Map data) {
        switch (action) {
            case "submit" -> llmService.triggerResponse(sessionId, data);
            case "cancel" -> cancelCurrentGeneration(sessionId);
            case "pause" -> pauseSession(sessionId);
        }
    }
}
```

interview-guide 的会话状态机：

| 状态        | 含义                           | 可转换到          |
| ----------- | ------------------------------ | ----------------- |
| IN_PROGRESS | 面试进行中                     | PAUSED, COMPLETED |
| PAUSED      | 暂停（用户离开页面或主动暂停） | IN_PROGRESS       |
| COMPLETED   | 面试结束                       | -                 |

## [后端：ASR 服务](https://javaguide.cn/ai/system-design/ai-voice.html#后端-asr-服务)

```java
// QwenAsrService.java
public void startTranscription(
    String sessionId,
    Consumer<String> onFinal,
    Consumer<String> onPartial,
    Runnable onReady,
    Consumer<Throwable> onError
) {
    // 1. 建立 WebSocket 连接到 DashScope ASR
    OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);

    // 2. 配置：开启服务端 VAD，400 ms 静音判定结束
    OmniRealtimeConfig config = OmniRealtimeConfig.builder()
        .enableTurnDetection(true)
        .turnDetectionSilenceDurationMs(400)
        .build();

    // 3. 注册回调：识别完成时触发
    conversation.updateSession(config);
    asrSession.markReady();
    onReady.run(); // 通知前端 asr_ready
}

public void sendAudio(String sessionId, byte[] audioData) {
    AsrSession session = sessions.get(sessionId);
    if (!session.awaitReady(1200)) {
        throw new IllegalStateException("ASR session not ready");
    }
    String audioBase64 = Base64.getEncoder().encodeToString(audioData);
    session.getConversation().appendAudio(audioBase64);
}
```

早期版本里，前端 WebSocket 一连上就允许用户点麦克风，但 DashScope ASR 的会话还没完全 ready，导致“第一题能说、第二题录不到”这类问题。现在后端在 `updateSession` 完成后才发送 `asr_ready`，前端在此之前禁用麦克风；如果 10 秒后仍未 ready，后端会自动重连 ASR，并推送 `asr_reconnecting` 给前端。

## [后端：TTS 服务](https://javaguide.cn/ai/system-design/ai-voice.html#后端-tts-服务)

```java
// QwenTtsService.java
public byte[] synthesize(String text) {
    CountDownLatch latch = new CountDownLatch(1);
    ByteArrayContainer audioContainer = new ByteArrayContainer();

    QwenTtsRealtime qwenTts = new QwenTtsRealtime(param, callback);
    qwenTts.connect();

    // 配置音色和参数
    QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
        .voice(voice)  // 如 "Cherry"
        .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
        .speechRate(speechRate)
        .build();

    qwenTts.updateSession(config);
    qwenTts.appendText(text);
    qwenTts.commit();

    // 等待音频块接收完成
    latch.await(30, TimeUnit.SECONDS);
    return audioContainer.toByteArray();
}
```

```java
// LLM 每输出一个完整句子，就提交给并发 TTS 队列
OrderedTtsChunkEmitter chunkEmitter = new OrderedTtsChunkEmitter(session, semaphore);
llmService.chatStreamSentences(userText, sentence -> {
    chunkEmitter.submit(sentence);
});

// TTS 分片按句子顺序推送，最后发送 audio_complete 控制消息
chunkEmitter.finish();
chunkEmitter.awaitCompletion();
```

服务端还会在所有 TTS 分片发送完成后额外推一个 `audio_complete` 控制消息。这样前端不再依赖某个音频分片必须带 `isLast=true`，即使某一句 TTS 合成失败，也能在已成功分片播放完后正确结束“面试官正在说话”的状态。

这里要压的是整段等待时间：**LLM 边生成句子，TTS 边合成，前端边播放**。后端用 `max-concurrent-tts-per-session` 控制单会话并发 TTS 数量，用 `tts-timeout-seconds` 避免某一句卡住整轮播放；如果所有句子级 TTS 都失败，再退回整段文本合成兜底

## [怎么让语音 Agent 支持打断？](https://javaguide.cn/ai/system-design/ai-voice.html#怎么让语音-agent-支持打断)

**播放层打断**：用户说话时，停止当前音频播放

**生成层打断**：取消服务端正在生成的 LLM 和 TTS

**上下文层打断**：正确记录已播放和未播放的内容

```typescript
// 前端：检测到用户说话时停止播放
const handleAudioData = (audioData: string) => {
  // AI 正在说话时，不发音频给后端
  if (isAiSpeakingRef.current) {
    return; // 静默丢弃，不触发打断逻辑
  }
  wsRef.current.sendAudio(audioData);
};

// 音频播放完成时
const finishAiPlayback = () => {
  aiAudioPendingRef.current = false;
  clearAudioPlaybackWatchdog();
  setAiSpeaking(false);
  setIsSubmitting(false);

  // 只有真正播放完的内容才能写入“已说”上下文
  commitAiMessage(aiTextRef.current.trim());
};
```

打断更接近“取消当前轮生成”，不是简单暂停。已播放的内容可以记为“已说”，未播放的内容不要提前写入历史。

### [状态机视角的打断](https://javaguide.cn/ai/system-design/ai-voice.html#状态机视角的打断)

| 当前状态     | 用户打断     | 正确响应                       |
| ------------ | ------------ | ------------------------------ |
| listening    | 用户插话     | 丢弃当前音频，重新开始识别     |
| thinking     | 用户补充     | 取消当前推理，用新输入重新触发 |
| speaking     | 用户插话     | 停止播放，清空队列             |
| tool_calling | 用户说“算了” | 取消工具调用，或停止后续播报   |

## [浏览器音频前处理管线](https://javaguide.cn/ai/system-design/ai-voice.html#浏览器音频前处理管线)

麦克风输入
    │
    ▼
┌─────────────────────────┐
│  AEC (回声消除)          │  消除扬声器播放的声音
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│  NS (噪声抑制)            │  压低背景噪声
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│  AGC (自动增益控制)       │  让音量更稳定
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│  VAD (语音活动检测)       │  判断是否有人声
└─────────────────────────┘
    │
    ▼
编码输出

## [级联链路和原生实时模型各有什么优劣](https://javaguide.cn/ai/system-design/ai-voice.html#级联链路和原生实时模型各有什么优劣)

### [方案一：级联式 ASR + LLM + TTS](https://javaguide.cn/ai/system-design/ai-voice.html#方案一-级联式-asr-llm-tts)

音频 -> VAD -> 流式 ASR -> LLM -> 流式 TTS -> 音频

### [方案二：原生 Realtime Speech-to-Speech](https://javaguide.cn/ai/system-design/ai-voice.html#方案二-原生-realtime-speech-to-speech)

音频 -> 原生多模态模型 -> 音频

高频、强实时、强自然感的语音产品，可以优先评估原生 Realtime API。强合规、强审计、强可控的业务场景，级联链路更稳。

## [怎么在生产环境中优化语音系统](https://javaguide.cn/ai/system-design/ai-voice.html#怎么在生产环境中优化语音系统)

### [缩短音频帧和提交粒度](https://javaguide.cn/ai/system-design/ai-voice.html#_1-缩短音频帧和提交粒度)

实时音频通常按 10 ms、20 ms、30 ms 分帧。帧太大延迟高，帧太小网络开销大。

interview-guide 的选择是 **200 ms 分块**：

这不会让 ASR 等到整句话结束才开始工作，但会给上行音频引入最多一个分块周期；再叠加服务端 VAD 的静音断句时间，用户会感到“话音落下后还要等一下”。如果要做得更好，可以：

- 减小分块到 100 ms
- 前端先发一小段让 ASR“热启动”
- 用服务端 VAD 的增量结果做流式 LLM 输入

### [让 LLM 先说短句](https://javaguide.cn/ai/system-design/ai-voice.html#_2-让-llm-先说短句)

### [TTS 按语义边界切分](https://javaguide.cn/ai/system-design/ai-voice.html#_3-tts-按语义边界切分)

### [控制上下文长度](https://javaguide.cn/ai/system-design/ai-voice.html#_4-控制上下文长度)

- **短期原文**：最近几轮完整转写和回答
- **会话摘要**：用户目标、已确认事实、未完成事项
- **事件状态**：当前播放进度、是否被打断、工具调用结果

## 语音 Agent 还能怎么演进

| 环节 | 当前                  | 演进方向                         |
| ---- | --------------------- | -------------------------------- |
| VAD  | 端侧 VAD + 服务端 VAD | 纯端侧 VAD，减少服务端压力       |
| ASR  | 纯云端                | 简单命令放端侧，复杂识别放云端   |
| LLM  | 纯云端                | 小模型端侧兜底，断网可用         |
| TTS  | 纯云端                | 固定提示音放端侧，自然对话放云端 |

## [打断体验优化](https://javaguide.cn/ai/system-design/ai-voice.html#打断体验优化)

目前 interview-guide 的打断是“静默丢弃”：AI 说话时用户的声音直接不发。这种方式简单，但体验不够自然。

- AI 说话时继续接收音频，但不发到 ASR
- 检测到用户声音后，先降低 AI 播放音量（渐变而不是突然停止）
- 打断后保留已播放内容的上下文

## [多模态扩展](https://javaguide.cn/ai/system-design/ai-voice.html#多模态扩展)

- **语音 + 屏幕共享**：面试官可以看到候选人的 IDE
- **语音 + 摄像头**：看候选人的表情和肢体语言
- **语音 + 白板**：一起画架构图

## 一次完整语音对话怎么跑

1. 音频采集：麦克风采集用户声音
2. 前处理：AEC 回声消除、NS 降噪、AGC 自动增益
3. VAD 检测：判断用户是否开始说话、是否说完
4. 音频上传：把音频流发给服务端
5. ASR 转写：把音频转成文字，最好是流式输出
6. 上下文组装：拼系统提示词、历史对话、工具定义
7. LLM 推理：理解用户意图，生成回答，必要时调用工具
8. TTS 合成：把文本回复转成音频
9. 音频下行：客户端边接收边播放
10. 状态回写：记录本轮对话状态，为下一轮准备上下文

## 面试版回答

### 怎么设计一个实时语音Agent

我会先把链路拆开：客户端通过 getUserMedia 采集麦克风音频，
做 AEC 回声消除、NS 降噪、AGC 自动增益，然后通过 VAD 判断用户是否开始说话和是否结束。
音频会按小块通过 WebSocket 或 WebRTC 上传到服务端，服务端接入流式 ASR，把音频实时转成文本。
然后由编排层组装上下文，调用 LLM 做意图理解、生成回答，必要时调用工具。
LLM 输出不能等整段完成，而是按句子流式交给 TTS 合成，前端收到音频分片后边收边播。

这个系统的难点不只是 ASR、LLM、TTS 三段调用，而是端到端延迟、打断处理、噪声环境、播放队列、状态机和成本观测。
特别是用户打断时，要同时停止前端播放、取消后端 LLM/TTS 流，并正确记录已播放和未播放内容。

### 级联式 ASR + LLM + TTS 和原生实时语音模型怎么选？

如果业务需要审计、可控、可替换供应商，比如客服、企业知识库、面试系统，我会优先用级联式 ASR + LLM + TTS。
因为 ASR 文本可以落库，LLM 仍然走文本 Agent 框架，TTS 也能独立替换。

如果是强实时、强自然感的语音产品，比如实时陪伴、语音通话、电话客服，可以评估原生 Speech-to-Speech API。
它端到端延迟更低，也能保留语气和停顿，但中间过程更黑盒，审计、成本和问题定位需要额外设计。
