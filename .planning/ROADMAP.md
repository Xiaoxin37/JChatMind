# Roadmap: JChatMind RAG Module Optimization

**Total Phases:** 5
**Requirements:** 22 v1 requirements mapped

## Phase 1: Multi-Format Document Parser

**Goal:** 系统能解析 TXT、DOCX、PDF、Excel/CSV 文件，替代仅支持 Markdown 的现状

**Requirements:** PARSE-01, PARSE-02, PARSE-03, PARSE-04, PARSE-05, PARSE-06

**Success Criteria:**
1. 上传 `.txt` 文件 → 正确提取纯文本内容
2. 上传 `.docx` 文件 → 提取文本并保留标题层级信息
3. 上传 `.pdf` 文件 → 提取段落和标题结构
4. 上传 `.xlsx`/`.csv` 文件 → 表格转为结构化文本
5. 上传后自动识别格式并路由到正确解析器
6. 现有 Markdown 解析能力不受影响

**Key Decisions:**
- 使用 Apache Tika 作为统一文档解析库（Java 生态标准方案）
- 解析器使用策略模式（Strategy Pattern），按 MIME type 路由
- 输出统一为 `ParsedDocument` 结构（含标题层级 + 纯文本内容）

**Dependencies:** 无（可独立开始）

**UI hint:** yes — 前端文件上传组件需支持多文件类型

---

## Phase 2: Semantic Chunking

**Goal:** 基于语义边界的智能 chunk 切分，替代纯标题切分

**Requirements:** CHUNK-01, CHUNK-02, CHUNK-03, CHUNK-04

**Success Criteria:**
1. Chunk 边界在段落/句子完整处，不截断语义
2. 可配置 chunk 最大 token/字符数
3. 每个 chunk 的 metadata 包含标题路径（如 "Chapter 1 > Section 2 > Subsection A"）
4. 兼容现有 `MarkdownSection` 输出格式和下游 embedding 流程

**Key Decisions:**
- 切分策略：先按标题拆分 → 再按段落/句子语义边界细拆 → 不超过 token 上限
- 句子边界使用标点符号 + 正则匹配，不依赖 NLP 库（保持轻量）
- 重叠窗口（overlap）避免跨 chunk 语义断裂

**Dependencies:** Phase 1（需要 ParsedDocument 作为输入）

**UI hint:** no

---

## Phase 3: BM25 + Hybrid Search

**Goal:** 新增 BM25 关键词检索，实现 BM25 + 向量的混合检索与 RRF 融合

**Requirements:** BM25-01, BM25-02, BM25-03, HYBRID-01, HYBRID-02, HYBRID-03

**Success Criteria:**
1. chunk 入库时自动建立 BM25 全文索引
2. 同一 query 分别执行 BM25 检索和向量检索，各自返回结果列表
3. RRF 融合后排序，结果优于单一检索方式
4. 返回结果数量可配置（top-K 参数化）
5. 文档删除/更新时 BM25 索引同步

**Key Decisions:**
- BM25 方案：在 PostgreSQL 中使用 pgroonga 或 Elasticsearch 轻量实例，或在 Java 中使用 Lucene/Solr 嵌入式 BM25
- 推荐方案：Java 内嵌 Lucene Analyzer 做 BM25 打分（避免引入外部依赖）
- RRF 融合公式：`score(d) = sum(1 / (k + rank_i(d)))`，k=60（标准值）

**Dependencies:** Phase 2（需要语义 chunk 作为检索单元）

**UI hint:** no

---

## Phase 4: Reranking

**Goal:** 引入 bge-reranker 对混合检索结果进行重排序

**Requirements:** RERANK-01, RERANK-02, RERANK-03

**Success Criteria:**
1. Ollama 部署 `qllama/bge-reranker-v2-m3` 模型
2. Java 通过 Ollama Embed API 调用 reranker（通过 cosine similarity 实现打分）
3. 混合检索结果经 reranker 后重新排序，top-N 结果相关性显著提升
4. Rerank 环节可配置开关（开发/调试时可跳过）

**Key Decisions:**
- Ollama 无原生 rerank API → 使用 Embed API 生成 (query, doc) pair 的 embedding → cosine similarity 打分
- 仅对混合检索后的 top-20 候选做 rerank（避免全量 rerank 的性能开销）
- Reranker 调用添加超时和降级策略（模型未部署时跳过 rerank）

**Dependencies:** Phase 3（需要混合检索结果作为 rerank 输入）

**UI hint:** no

---

## Phase 5: End-to-End Integration

**Goal:** 全链路跑通：上传 → 解析 → 切分 → 入库 → 混合检索 → rerank → 返回结果

**Requirements:** E2E-01, E2E-02, E2E-03

**Success Criteria:**
1. 上传非 Markdown 文件（TXT/PDF/DOCX/Excel）→ 全流程正确 → 知识库中可检索到
2. Agent 提问时走完整检索链路，返回结果质量肉眼优于当前纯向量检索
3. 数据库 schema 兼容所有新增字段（BM25 索引、chunk metadata 等）
4. 前端能上传新格式文件并展示处理状态
5. 无回归：现有 Markdown 上传和检索功能正常工作

**Key Decisions:**
- 需要更新 `DocumentFacadeServiceImpl` 调度新解析器
- 需要更新 `RagServiceImpl` 走混合检索 + rerank 链路
- 需要更新 `ChunkBgeM3Mapper.xml` 和数据库 schema

**Dependencies:** Phase 1, 2, 3, 4（所有前置阶段）

**Plans:** 3 plans

Plans:
- [ ] 5-01-PLAN.md — pgvector L2 to cosine migration (SQL + MyBatis XML)
- [ ] 5-02-PLAN.md — Wire HybridSearchService into Agent KnowledgeTools
- [ ] 5-03-PLAN.md — E2E integration tests (upload->parse->chunk->search->rerank)

**UI hint:** yes — 前端上传状态展示
