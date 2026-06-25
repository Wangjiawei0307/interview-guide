# RAG 文档多模态解析与 Chunk 切分策略改造

参考资料：https://javaguide.cn/ai/rag/rag-document-processing.html

## 本次改造目标

这次把知识库上传链路从“只抽纯文本 + 默认 TokenTextSplitter”升级为“结构化文档解析 + 按内容类型选择切分策略”。核心思路是先把文件解析成统一的 `Text / Table / Image` 文档块，再在向量化阶段根据块类型做最合适的 chunk 处理。

## 核心代码位置

- `DocumentParseService`：负责 PDF、Word、Excel、CSV、图片的解析。
- `ParsedKnowledgeDocument` / `ParsedDocumentBlock`：统一的结构化解析结果模型。
- `KnowledgeDocumentPayloadCodec`：把结构化文档编码进 Redis Stream 任务，同时兼容旧的纯文本任务。
- `KnowledgeBaseUploadService`：上传后生成结构化文档载荷，再发送向量化任务。
- `KnowledgeBaseVectorService`：按块类型切分并写入 PgVector，同时回写 `chunkCount`。
- `FileValidationService`：放开 Excel、CSV、图片等知识库文件类型。

## 多格式解析设计

PDF：继续使用 Apache Tika，并开启 `PDFParserConfig#setSortByPosition(true)`，尽量按页面坐标顺序抽取文本，缓解多栏 PDF 文本顺序错乱问题。PDF 当前仍以文本块为主。

Word：对 `.docx` 使用 Apache POI XWPF 解析段落、标题和表格。标题会被保存为 `section_path`，表格会单独转成 Markdown 表格和 JSONL 行，避免把表格拆散后丢失行列关系。旧 `.doc` 走 Tika 兜底。

Excel / CSV：用 POI `WorkbookFactory` 和 CSV 解析逻辑读取 sheet/行/列，按每 50 行切成一个表格块。每个表格块同时保留 Markdown 表格和 JSONL 行，保证模型既能读表格展示，也能理解“字段-值”的关系。

图片：用 `ImageIO` 读取图片宽高等基础元数据，生成 `Image` 块并写入 metadata。当前没有强行内置 OCR 或视觉 caption，因为这类能力依赖外部 OCR/多模态模型；代码里通过 `needs_ocr=true` 和 `image_metadata_placeholder` 预留了后续接入点。

## Chunk 策略

文本块：采用递归式切分，优先按段落切，超长段落再按句末符号或硬边界切。目标长度约 `1800` 字符，并保留约 `220` 字符 overlap，减少跨 chunk 语义断裂。

Word 文本：使用 `section_recursive_overlap`，保留章节路径，让检索结果能知道内容来自哪个标题层级。

PDF 文本：使用 `layout_text_recursive_overlap`，配合 Tika 位置排序后的文本抽取。

表格块：使用 `table_standalone`，不再按普通文本拆分。原因是表格一旦按固定长度切开，很容易把表头和数据行拆散，导致 RAG 回答时字段关系丢失。

图片块：使用 `image_metadata_placeholder`，当前以图片基础元数据入库，后续可升级为 OCR/caption 文本入库。

## 元数据增强

每个向量文档都会写入：

- `kb_id`：知识库 ID，用于检索过滤。
- `source_type`：pdf / word / excel / csv / image / markdown / text。
- `block_type`：text / table / image。
- `chunk_strategy`：本 chunk 使用的切分策略。
- `chunk_index` / `chunk_part_index`：chunk 顺序。
- `section_path`：Word 标题路径。
- `sheet_name` / `row_start` / `row_end`：Excel/CSV 表格来源。
- `image_width` / `image_height`：图片基础信息。

## 简历可写版本

负责 RAG 知识库文档处理链路升级，基于 Apache Tika、Apache POI、ImageIO 实现 PDF、Word、Excel、CSV、图片等多格式解析，将上传文件统一抽象为 Text/Table/Image 结构化文档块；设计表格独立切分、Word 标题层级保留、文本递归重叠切分和元数据增强策略，并通过 Redis Stream 将结构化解析结果异步送入 PgVector，提升复杂文档的检索可解释性和 RAG 命中质量。

## 面试回答版本

我在项目里没有直接把上传文件简单转成一整段文本，而是先做了一层结构化文档解析。PDF 走 Tika 并按位置排序抽取文本，Word 会识别标题层级和表格，Excel/CSV 会按 sheet 和行列关系转成 Markdown 表格加 JSONL，图片先保留基础元数据并预留 OCR/caption 扩展点。向量化时也不是统一固定长度切分，而是根据块类型选择策略：普通文本按段落递归切分并保留 overlap，Word 额外保留 section_path，表格作为独立 chunk 不拆散，避免表头和数据行分离。最后每个 chunk 都带上来源类型、章节路径、sheet 名、行范围、切分策略等 metadata，方便后续按知识库过滤、排查召回结果和提升 RAG 回答的可控性。