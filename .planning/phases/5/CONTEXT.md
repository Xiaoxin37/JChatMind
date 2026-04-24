# Phase 5: End-to-End Integration - Context

**Gathered:** 2026-04-23
**Status:** Ready for planning

<domain>
## Phase Boundary

全链路跑通：上传 → 解析 → 切分 → 入库 → 混合检索 → rerank → 返回结果。

Phase 1-4 的核心代码已实现（解析器、切分器、BM25、混合检索、rerank），但 **HybridSearchService 未被任何消费者调用**。Phase 5 需要将混合检索嵌入 Agent 流程，修正向量距离函数，并通过集成测试验证全链路。

**Requirements:** E2E-01, E2E-02, E2E-03

</domain>

<decisions>
## Implementation Decisions

### 检索入口接入方式
- **D-01:** 在 Agent 的 knowledge-retrieval 步骤中，**直接替换** `RagService.similaritySearch()` 为 `HybridSearchService.search()`
- **D-02:** 不加配置开关，直接使用 HybridSearchService，不设 fallback 到纯向量检索（各子环节已有独立降级逻辑）

### 向量距离函数
- **D-03:** 将 pgvector 索引从 `vector_l2_ops` 改为 `vector_cosine_ops`，SQL 操作符从 `<->` 改为 `<=>`，匹配 bge-m3 归一化向量的语义

### E2E 验证方式
- **D-04:** 编写 Spring Boot `@SpringBootTest` 集成测试，自动化验证各格式文件上传→解析→切分→入库→检索全流程

### Claude's Discretion
- 集成测试的具体用例设计和测试数据构造
- 数据库迁移脚本的编写方式（手动 SQL vs Flyway/Liquibase）
- Agent 流程中替换检索调用的具体代码位置（需阅读代码确认）

</decisions>

<canonical_refs>
## Canonical References

### 检索链路
- `.planning/ROADMAP.md` §Phase 5 — 全链路 E2E 目标和成功标准
- `.planning/REQUIREMENTS.md` §End-to-End — E2E-01/02/03 需求定义

### 核心代码文件（需修改）
- `jchatmind/src/main/java/com/kama/jchatmind/service/impl/HybridSearchServiceImpl.java` — 混合检索实现（已存在，待接入）
- `jchatmind/src/main/java/com/kama/jchatmind/service/HybridSearchService.java` — 混合检索接口
- `jchatmind/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java` — 现有纯向量检索（将被替换）
- `jchatmind/src/main/resources/mapper/ChunkBgeM3Mapper.xml` — pgvector SQL（需改 `<->` 为 `<=>`）
- `jchatmind/src/main/resources/application.yaml` — 检索配置

### 数据库
- `jchatmind_assert/jchatmind.sql` — 数据库 DDL（需更新索引定义）

### Agent 流程
- `jchatmind/src/main/java/com/kama/jchatmind/agent/` — Agent Think-Execute 循环（需确认检索调用点）

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **HybridSearchServiceImpl** — 完整的 BM25 + 向量 + RRF + Rerank 链路已实现，零修改即可接入
- **DocumentFacadeServiceImpl** — 完整的上传→解析→切分→入库流程已支持多格式（md/txt/docx/pdf/xlsx/csv）
- **RagServiceImpl.rerank()** — cosine similarity 降级策略已实现

### Established Patterns
- Spring Boot 3.5.8 + MyBatis 3.0.3 架构
- 策略模式用于文档解析器路由
- Ollama Embed API 通过 WebClient 调用

### Integration Points
- `HybridSearchService.search(kbId, query, topK)` 的签名与 `RagService.similaritySearch(kbId, query, topK)` 兼容
- Agent 流程中调用检索的位置需要阅读代码确认（可能在 Think 工具的 knowledge-retrieval 步骤中）

### 已知问题
- BM25 索引使用 Java 内嵌 Lucene（文件存储在 `./data/bm25-index`），非数据库字段
- 当前 `similaritySearch` 使用 L2 距离但 bge-m3 输出归一化向量，语义不匹配

</code_context>

<deferred>
## Deferred Ideas

- 配置级检索模式切换（hybrid/vector）— 用户决定不在本次 Phase 引入
- 前端上传状态展示 — ROADMAP.md 中标注为 UI hint，但本次聚焦后端集成测试

</deferred>

---

*Phase: 5-e2e-integration*
*Context gathered: 2026-04-23*
