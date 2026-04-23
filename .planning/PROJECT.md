# JChatMind RAG Module Optimization

## What This Is

优化 JChatMind 项目的 RAG（检索增强生成）模块，使其支持多格式文档的智能语义切分和混合检索（BM25 + 向量 + Rerank）。当前 RAG 模块仅支持 Markdown 文件按标题切分，且仅使用 pgvector 的 L2 距离做纯向量相似度搜索。

## Core Value

用户上传任意格式的文档后，能检索到真正相关的知识片段 — 这直接决定了 AI Agent 回答质量的上限。

## Requirements

### Validated

- ✓ 已有 Markdown 文件按标题切分能力 — 现有
- ✓ 已有 BGE-M3 向量 embedding 能力（Ollama 本地部署） — 现有
- ✓ 已有 pgvector IVFFlat 向量索引 + L2 相似度搜索 — 现有
- ✓ 已有 Agent Think-Execute 循环 + SSE 实时推送 — 现有

### Active

- [ ] 支持 TXT、DOCX、PDF、Excel/CSV 多格式文档解析
- [ ] 基于语义边界的智能 chunk 切分（替代纯标题切分）
- [ ] 新增 BM25 关键词检索能力
- [ ] BM25 + 向量混合检索（Hybrid Search）
- [ ] 本地 bge-reranker 重排序环节
- [ ] 多路召回 + RRF 分数融合
- [ ] 端到端跑通：上传非 Markdown 文档 → 正确切分 → 混合检索 → 返回结果

### Out of Scope

- [ ] 图片/视频等多媒体内容解析 — 当前阶段不涉及，后续可考虑 OCR
- [ ] Rerank 模型训练/微调 — 使用预训练模型即可
- [ ] 查询重写（Query Rewriting） — 当前版本不涉及

## Context

**当前技术栈：**
- Backend: Java 17, Spring Boot 3.5.8, Spring AI 1.1.0, MyBatis 3.0.3
- Database: PostgreSQL + pgvector (IVFFlat index)
- Embedding: BGE-M3 (1024d) via Ollama at localhost:11434
- LLM: DeepSeek / Zhipu AI (switchable)
- Frontend: React 19, TypeScript, Ant Design 6, Vite
- 当前解析器: Flexmark (仅 Markdown)

**关键文件：**
- `RagServiceImpl.java` — 核心 RAG 服务（embedding + 搜索）
- `MarkdownParserServiceImpl.java` — 当前解析实现
- `DocumentFacadeServiceImpl.java` — 文档上传和解析调度
- `ChunkBgeM3Mapper.xml` — pgvector SQL
- `jchatmind.sql` — 数据库 DDL

**已知限制：**
- 仅支持 `.md` 文件
- 按标题切分，粒度粗
- 纯向量检索，无关键词/混合检索
- 固定返回 top-3，无 reranking
- Embedding 只使用标题文本，未使用 chunk 全文

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 使用语义切分替代标题切分 | 标题切分对非 Markdown 格式不适用，且语义不连续 | — Pending |
| BM25 + 向量混合检索 | 纯向量检索对精确关键词匹配不敏感，BM25 互补 | — Pending |
| 本地 bge-reranker 做重排序 | Ollama 已有模型，无需额外 API 依赖 | — Pending |
| 保持 pgvector 作为向量存储 | 已有基础设施，无需引入 Milvus/Qdrant 等专用向量库 | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-23 after initialization*
