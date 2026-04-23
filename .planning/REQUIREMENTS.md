# Requirements: JChatMind RAG Module Optimization

**Defined:** 2026-04-23
**Core Value:** 用户上传任意格式的文档后，能检索到真正相关的知识片段 — 这直接决定了 AI Agent 回答质量的上限

## v1 Requirements

### Document Parsing

- [ ] **PARSE-01**: 系统能解析 `.txt` 纯文本文件并提取内容
- [ ] **PARSE-02**: 系统能解析 `.docx` Word 文档并提取文本（含标题层级）
- [ ] **PARSE-03**: 系统能解析 `.pdf` 文档并提取文本（含段落、标题结构）
- [ ] **PARSE-04**: 系统能解析 `.xlsx`/`.csv` 文件并将表格转为结构化文本
- [ ] **PARSE-05**: 系统能解析 `.md` 文件（保持现有能力）
- [ ] **PARSE-06**: 上传文件后自动识别文件格式并路由到对应解析器

### Semantic Chunking

- [ ] **CHUNK-01**: Chunk 切分基于语义边界（段落/句子完整性），而非纯标题
- [ ] **CHUNK-02**: Chunk 大小可配置（token 数或字符数上限）
- [ ] **CHUNK-03**: 保留文档层级结构信息（标题路径）作为 chunk metadata
- [ ] **CHUNK-04**: 兼容现有 `MarkdownSection` 输出格式，不破坏上游调用

### BM25 Search

- [ ] **BM25-01**: 系统能对 chunk 内容建立 BM25 关键词索引
- [ ] **BM25-02**: 支持 BM25 关键词检索并返回打分结果
- [ ] **BM25-03**: BM25 索引随文档增删自动更新

### Hybrid Retrieval

- [ ] **HYBRID-01**: 检索时同时执行 BM25 和向量相似度搜索
- [ ] **HYBRID-02**: 使用 RRF（Reciprocal Rank Fusion）融合 BM25 和向量结果
- [ ] **HYBRID-03**: 融合后的 top-K 结果数量可配置（替代硬编码 top-3）

### Reranking

- [ ] **RERANK-01**: 融合结果经过 bge-reranker 重排序
- [ ] **RERANK-02**: bge-reranker 通过 Ollama 本地部署（`ollama pull qllama/bge-reranker-v2-m3`）
- [ ] **RERANK-03**: Rerank 返回最终打分后的 top-N 个 chunk 内容

### End-to-End

- [ ] **E2E-01**: 上传非 Markdown 文件（TXT/PDF/DOCX/Excel）→ 正确解析 → 语义切分 → 入库
- [ ] **E2E-02**: Agent 查询时走混合检索 + rerank → 返回结果优于当前纯向量检索
- [ ] **E2E-03**: 数据库 schema 兼容新增的 BM25 索引字段

## v2 Requirements

### Query Enhancement

- **QUERY-01**: 查询重写（Query Rewriting）以提升检索质量
- **QUERY-02**: 多轮对话中利用历史上下文增强检索

### Advanced Chunking

- **CHUNK-05**: 根据文档类型自适应选择切分策略
- **CHUNK-06**: 图片/表格的 OCR 和结构化提取

### Observability

- **OBS-01**: 检索质量打分和日志记录
- **OBS-02**: 检索结果相关性可视化展示

## Out of Scope

| Feature | Reason |
|---------|--------|
| 图片/视频解析 | 需要 OCR/多媒体处理，超出当前范围 |
| Reranker 模型微调 | 使用预训练 bge-reranker 即可满足需求 |
| 专用向量库迁移（Milvus/Qdrant） | pgvector 已满足当前规模 |
| 实时流式检索 | 非核心需求，可后续添加 |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| PARSE-01 | Phase 1 | Pending |
| PARSE-02 | Phase 1 | Pending |
| PARSE-03 | Phase 1 | Pending |
| PARSE-04 | Phase 1 | Pending |
| PARSE-05 | Phase 1 | Pending |
| PARSE-06 | Phase 1 | Pending |
| CHUNK-01 | Phase 2 | Pending |
| CHUNK-02 | Phase 2 | Pending |
| CHUNK-03 | Phase 2 | Pending |
| CHUNK-04 | Phase 2 | Pending |
| BM25-01 | Phase 3 | Pending |
| BM25-02 | Phase 3 | Pending |
| BM25-03 | Phase 3 | Pending |
| HYBRID-01 | Phase 3 | Pending |
| HYBRID-02 | Phase 3 | Pending |
| HYBRID-03 | Phase 3 | Pending |
| RERANK-01 | Phase 4 | Pending |
| RERANK-02 | Phase 4 | Pending |
| RERANK-03 | Phase 4 | Pending |
| E2E-01 | Phase 5 | Pending |
| E2E-02 | Phase 5 | Pending |
| E2E-03 | Phase 5 | Pending |

**Coverage:**
- v1 requirements: 22 total
- Mapped to phases: 22
- Unmapped: 0

---
*Requirements defined: 2026-04-23*
*Last updated: 2026-04-23 after initial definition*
