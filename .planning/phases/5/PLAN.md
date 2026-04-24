---
phase: 5
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - jchatmind/src/main/resources/mapper/ChunkBgeM3Mapper.xml
  - jchatmind_assert/jchatmind.sql
autonomous: true
requirements: E2E-03
user_setup:
  - service: postgresql
    why: "pgvector index migration requires database access"
    action: "Ensure PostgreSQL is running with pgvector extension enabled"
    command: "Run the migration SQL manually or via psql"
---

# Phase 5 Plan 01: pgvector L2 to Cosine Migration

<objective>
Migrate pgvector distance function from L2 (`<->`) to cosine (`<=>`) in both MyBatis SQL and DDL schema, and recreate the IVFFlat index with `vector_cosine_ops`. This is required for E2E-03 (database schema compatibility) and ensures bge-m3 normalized vectors use the semantically correct distance metric.

Purpose: bge-m3 outputs L2-normalized vectors. L2 distance on normalized vectors approximates cosine but is semantically incorrect. Cosine distance via `<=>` is the proper metric and ensures the pgvector IVFFlat index is actually used (not a sequential scan).
Output: Updated SQL in two files, migration script to run against live database.
</objective>

<execution_context>
@.planning/ROADMAP.md
@.planning/STATE.md
@.planning/phases/5/CONTEXT.md
@.planning/phases/5/5-RESEARCH.md
</execution_context>

<context>
@jchatmind/src/main/resources/mapper/ChunkBgeM3Mapper.xml — line 110 has `ORDER BY embedding <-> #{vectorLiteral}::vector`
@jchatmind_assert/jchatmind.sql — line 86 has `USING ivfflat (embedding vector_l2_ops)`

Decision D-03: pgvector index from vector_l2_ops to vector_cosine_ops, SQL operator from <-> to <=>
</context>

<tasks>

<task type="auto">
  <name>Task 1: Update ChunkBgeM3Mapper.xml — change <-> to <=></name>
  <files>jchatmind/src/main/resources/mapper/ChunkBgeM3Mapper.xml</files>
  <action>In the `similaritySearch` SQL query (around line 110), change the pgvector distance operator from `<->` (L2 distance) to `<=>` (cosine distance). The line should read:
`ORDER BY embedding <=> #{vectorLiteral}::vector`
Do not change any other part of the query. This is a one-character operator change.</action>
  <verify>
    <automated>grep -n '<=>' jchatmind/src/main/resources/mapper/ChunkBgeM3Mapper.xml</automated>
  </verify>
  <done>ChunkBgeM3Mapper.xml uses `<=>` operator in similaritySearch query, no `<->` remains in that query</done>
</task>

<task type="auto">
  <name>Task 2: Update jchatmind.sql — change index opclass and add migration comments</name>
  <files>jchatmind_assert/jchatmind.sql</files>
  <action>In the `CREATE INDEX idx_chunk_embedding` statement (line 86), change `vector_l2_ops` to `vector_cosine_ops`. The line should read:
`USING ivfflat (embedding vector_cosine_ops)`

Additionally, add a migration block AFTER the table creation section that documents the live migration steps:
```sql
-- Migration: L2 -> Cosine (Phase 5)
-- Run against existing databases to recreate index with correct opclass
-- DROP INDEX IF EXISTS idx_chunk_embedding;
-- CREATE INDEX idx_chunk_embedding ON chunk_bge_m3 USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```
Comment out the migration commands (they are destructive and should be run manually, not as part of a fresh schema creation).</action>
  <verify>
    <automated>grep -n 'vector_cosine_ops' jchatmind_assert/jchatmind.sql</automated>
  </verify>
  <done>jchatmind.sql DDL uses vector_cosine_ops, migration SQL is present as commented instructions</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| MyBatis XML -> PostgreSQL | SQL operator change; parameterized queries already prevent injection |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-5-01 | Tampering | ChunkBgeM3Mapper.xml SQL | mitigate | Parameterized queries via `#{}` syntax already in place; operator change does not introduce injection surface |
| T-5-02 | Denial of Service | IVFFlat index recreation | accept | Index rebuild is a one-time admin operation; low-value target |
</threat_model>

<verification>
- Grep for `<=>` in ChunkBgeM3Mapper.xml — must exist in similaritySearch
- Grep for `<->` in ChunkBgeM3Mapper.xml — must NOT exist in similaritySearch
- Grep for `vector_cosine_ops` in jchatmind.sql — must exist
- Grep for `vector_l2_ops` in jchatmind.sql — must NOT exist in active (non-commented) lines
</verification>

<success_criteria>
- Both files use cosine distance operator (`<=>`) and `vector_cosine_ops`
- Migration SQL is documented for live database execution
- No other SQL queries in the project are affected (only similaritySearch uses vector distance)
</success_criteria>

<output>
After completion, create `.planning/phases/5/5-01-SUMMARY.md`
</output>

---

---
phase: 5
plan: 02
type: execute
wave: 1
depends_on: []
files_modified:
  - jchatmind/src/main/java/com/kama/jchatmind/agent/tools/KnowledgeTools.java
autonomous: true
requirements: E2E-02
---

# Phase 5 Plan 02: Wire HybridSearchService into Agent Knowledge Tools

<objective>
Replace `RagService` with `HybridSearchService` in `KnowledgeTools.java` — the single call site where the Agent invokes knowledge retrieval. This directly implements decisions D-01 and D-02: direct replacement, no config switch, no fallback.

Purpose: The Agent currently calls `ragService.similaritySearch(kbsId, query)` which returns pure vector results. After this change, it will call `hybridSearchService.search(kbsId, query, topK)` which returns BM25 + vector + RRF + rerank results.
Output: Modified KnowledgeTools.java with HybridSearchService dependency.
</objective>

<execution_context>
@.planning/ROADMAP.md
@.planning/STATE.md
@.planning/phases/5/CONTEXT.md
@.planning/phases/5/5-RESEARCH.md
</execution_context>

<context>
@jchatmind/src/main/java/com/kama/jchatmind/agent/tools/KnowledgeTools.java — current implementation with RagService dependency

Decision D-01: Directly replace RagService.similaritySearch() with HybridSearchService.search()
Decision D-02: No config switch, no fallback to pure vector search

Key interface from HybridSearchService.java:
```java
List<HybridResult> search(String kbId, String query, int topK);

class HybridResult {
    public final String chunkId;
    public final String content;
    public final String metadata;
    public final double rrfScore;
}
```

The old `similaritySearch(kbId, query)` returned `List<String>` (just content). The new `search(kbId, query, topK)` returns `List<HybridResult>` (with chunkId, content, metadata, rrfScore). We need to extract the `content` field to maintain the same return type for the Agent.
</context>

<tasks>

<task type="auto">
  <name>Task 1: Replace RagService with HybridSearchService in KnowledgeTools</name>
  <files>jchatmind/src/main/java/com/kama/jchatmind/agent/tools/KnowledgeTools.java</files>
  <action>Make these changes to KnowledgeTools.java:

1. Replace import: `import com.kama.jchatmind.service.RagService;` → `import com.kama.jchatmind.service.HybridSearchService;`

2. Replace field: `private final RagService ragService;` → `private final HybridSearchService hybridSearchService;`

3. Replace constructor parameter: `public KnowledgeTools(RagService ragService)` → `public KnowledgeTools(HybridSearchService hybridSearchService)`

4. Replace assignment: `this.ragService = ragService;` → `this.hybridSearchService = hybridSearchService;`

5. Replace the knowledgeQuery method body:
```java
// OLD:
List<String> strings = ragService.similaritySearch(kbsId, query);
return String.join("\n", strings);

// NEW:
List<HybridSearchService.HybridResult> results = hybridSearchService.search(kbsId, query, 5);
return results.stream()
        .map(HybridSearchService.HybridResult::content)
        .collect(java.util.stream.Collectors.joining("\n"));
```

The `5` for topK matches the existing default retrieval count. Do NOT add any config toggle or fallback logic (per D-02). Do NOT delete RagService — it is still used internally by HybridSearchServiceImpl for embedding and reranking.</action>
  <verify>
    <automated>grep -n 'HybridSearchService' jchatmind/src/main/java/com/kama/jchatmind/agent/tools/KnowledgeTools.java</automated>
  </verify>
  <done>KnowledgeTools imports and uses HybridSearchService, no reference to RagService remains, knowledgeQuery returns joined content from HybridResult::content</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Agent -> KnowledgeTools | Tool receives kbsId (UUID) and query (string) from LLM — both are validated upstream |
| KnowledgeTools -> HybridSearchService | Internal service call — no external trust boundary |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-5-03 | Tampering | knowledgeQuery parameters | mitigate | kbId is validated as UUID by PostgreSQL; query string is passed through to search service without SQL concatenation (parameterized via MyBatis) |
| T-5-04 | Elevation of Privilege | Agent tool exposure | accept | Tools are server-side only, not exposed as REST endpoints; LLM can only call tools within its configured scope |
</threat_model>

<verification>
- Spring Boot context starts without bean wiring errors (HybridSearchService is injectable as @Service)
- KnowledgeTools compiles without errors
- No RagService reference remains in KnowledgeTools.java
- knowledgeQuery returns same format (joined string) as before
</verification>

<success_criteria>
- KnowledgeTools compiles and Spring context starts cleanly
- Agent tool `KnowledgeTool` returns content from hybrid search results
- Top-K is set to 5 (configurable in application.yaml via `rag.retrieval.top-k`)
- RagService is NOT deleted (still needed by HybridSearchServiceImpl internally)
</success_criteria>

<output>
After completion, create `.planning/phases/5/5-02-SUMMARY.md`
</output>

---

---
phase: 5
plan: 03
type: execute
wave: 2
depends_on: [01, 02]
files_modified:
  - jchatmind/src/test/java/com/kama/jchatmind/integration/E2EIntegrationTests.java
autonomous: true
requirements: E2E-01, E2E-02
---

# Phase 5 Plan 03: E2E Integration Tests

<objective>
Write `@SpringBootTest` integration tests that validate the full pipeline: upload -> parse -> chunk -> embed -> index -> hybrid search -> rerank. This implements decision D-04 and addresses requirements E2E-01 (multi-format upload) and E2E-02 (hybrid search + rerank).

Purpose: Automated verification that all Phase 1-4 components work together end-to-end. Tests prove that non-Markdown files flow through the entire pipeline and return quality search results.
Output: E2EIntegrationTests.java with per-format upload tests and full pipeline test.
</objective>

<execution_context>
@.planning/ROADMAP.md
@.planning/STATE.md
@.planning/phases/5/CONTEXT.md
@.planning/phases/5/5-RESEARCH.md
@.planning/phases/1/PLAN.md — parser patterns
@.planning/phases/2/PLAN.md — chunking patterns
@.planning/phases/3/PLAN.md — BM25/hybrid patterns
@.planning/phases/4/PLAN.md — reranking patterns
</execution_context>

<context>
@jchatmind/src/test/java/com/kama/jchatmind/JchatmindApplicationTests.java — existing @SpringBootTest pattern (empty contextLoads test)
@jchatmind/src/main/java/com/kama/jchatmind/service/DocumentFacadeService.java — uploadDocument(kbId, MultipartFile) interface
@jchatmind/src/main/java/com/kama/jchatmind/service/HybridSearchService.java — search(kbId, query, topK) returns List<HybridResult>

Existing test infrastructure: JUnit 5.12.2 + Mockito 5.17.0 via Spring Boot parent. spring-boot-starter-test is in pom.xml.

Key services to autowire:
- DocumentFacadeService — for upload->parse->chunk->embed->index
- HybridSearchService — for search verification
- KnowledgeBaseFacadeService — for test KB creation/cleanup

Test data: Use MockMultipartFile with inline content for TXT, and minimal valid content for other formats. No external test files needed.
</context>

<tasks>

<task type="auto">
  <name>Task 1: Create integration test class with test KB lifecycle</name>
  <files>jchatmind/src/test/java/com/kama/jchatmind/integration/E2EIntegrationTests.java</files>
  <action>Create the test class at `jchatmind/src/test/java/com/kama/jchatmind/integration/E2EIntegrationTests.java` with:

1. Package: `com.kama.jchatmind.integration`
2. Annotations: `@SpringBootTest`
3. Autowired services:
   - `DocumentFacadeService documentFacadeService`
   - `HybridSearchService hybridSearchService`
   - `KnowledgeBaseFacadeService kbFacadeService` (if exists; otherwise use existing KB or direct DB insert)

4. Test KB lifecycle:
   - `@BeforeEach`: Create a test knowledge base, store its ID in a `@TestInstance` field
   - `@AfterEach`: Clean up the test KB (delete documents, delete KB record)
   - If KnowledgeBaseFacadeService does not exist, use `@Sql` to insert a test KB directly, or use a hardcoded existing KB ID from the database

5. Helper method `createTestMultipartFile(String filename, String mimeType, String content)` that returns a `MockMultipartFile`

Do NOT use `@Transactional` on the test class — the pipeline writes to filesystem (Lucene index) which is not transactional.
</action>
  <verify>
    <automated>mvn test -Dtest=E2EIntegrationTests#contextCheck -q</automated>
  </verify>
  <done>Test class compiles, Spring context starts, @BeforeEach creates KB, @AfterEach cleans up</done>
</task>

<task type="auto">
  <name>Task 2: Write per-format upload-parse-search tests (E2E-01)</name>
  <files>jchatmind/src/test/java/com/kama/jchatmind/integration/E2EIntegrationTests.java</files>
  <action>Add the following test methods to E2EIntegrationTests.java:

**test01_fullPipeline_txtFile:**
- Content: "Spring Boot is a popular Java framework for building microservices and REST APIs"
- File: `test-pipeline.txt`, MIME: `text/plain`
- Steps: upload via documentFacadeService.uploadDocument(kbId, file) -> assert document ID returned -> wait 1 second for async processing -> call hybridSearchService.search(kbId, "Java framework microservices", 5) -> assert results not empty and contain "Spring Boot"

**test02_fullPipeline_pdfFile:**
- Use a minimal valid PDF with known text content. Create as bytes:
```java
byte[] pdfContent = ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n4 0 obj\n<< /Length 44 >>\nstream\nBT\n/F1 12 Tf\n100 700 Td\n(Apache Kafka is a distributed event streaming platform.) Tj\nET\nendstream\nendobj\n5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\nxref\n0 6\ntrailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n0\n%%EOF").getBytes();
```
- File: `test-pipeline.pdf`, MIME: `application/pdf`
- Steps: upload -> assert ID -> search for "distributed event streaming" -> assert results contain "Apache Kafka"

**test03_fullPipeline_docxFile:**
- Create minimal DOCX (ZIP-based). A DOCX is a ZIP file with specific XML structure. Use a pre-constructed minimal DOCX byte array or a known-good test fixture.
- Simpler approach: if the parser uses Tika (which handles corrupted/minimal files gracefully), create a small valid DOCX using Apache POI's XWPFDocument in a `@BeforeAll` static setup, write to byte array, then use as MockMultipartFile.
- Content: "Redis is an in-memory data store used for caching and real-time analytics"
- Steps: upload -> search for "in-memory data store caching" -> assert results contain "Redis"

**test04_fullPipeline_csvFile:**
- Content: `name,description,value\nCPU,Processor speed,3.5GHz\nRAM,Memory capacity,16GB`
- File: `test-pipeline.csv`, MIME: `text/csv`
- Steps: upload -> search for "processor speed memory" -> assert results contain table content

Each test must:
1. Call uploadDocument and assert a document ID is returned
2. Allow a brief pause (Thread.sleep(1000)) if processing is async
3. Call hybridSearchService.search and assert non-empty results
4. Assert that at least one result's content field contains expected keywords

If the test environment does not have Ollama running, the rerank step will degrade gracefully (per existing implementation). BM25 may return empty if Lucene index is not initialized — in that case, the test should still pass if vector search returns results (HybridSearchService degrades gracefully).
</action>
  <verify>
    <automated>mvn test -Dtest=E2EIntegrationTests#test01_fullPipeline_txtFile -q</automated>
  </verify>
  <done>Four format-specific tests exist (txt, pdf, docx, csv), each uploads a file, processes it, and verifies search returns relevant content</done>
</task>

<task type="auto">
  <name>Task 3: Write hybrid search + rerank quality test (E2E-02)</name>
  <files>jchatmind/src/test/java/com/kama/jchatmind/integration/E2EIntegrationTests.java</files>
  <action>Add test method `test05_hybridSearchReturnsRerankedResults`:

1. First upload a TXT file with semantically rich content:
```
"Machine learning is a subset of artificial intelligence that focuses on algorithms that learn from data. Deep learning is a subset of machine learning using neural networks with multiple layers. Natural language processing enables machines to understand and generate human text."
```

2. Call `hybridSearchService.search(kbId, "neural networks deep learning AI", 5)`

3. Verify:
   - Results list is not empty
   - Each HybridResult has a non-null chunkId, content, and rrfScore > 0
   - At least one result contains "deep learning" or "neural networks" or "machine learning"
   - The rrfScore of the most relevant result is higher than the least relevant (verifies RRF fusion is working)

4. Additionally, write `test06_knowledgeToolReturnsContent` that verifies the agent tool path:
   - Autowire or instantiate KnowledgeTools with the HybridSearchService
   - Call `knowledgeTools.knowledgeQuery(kbId, "machine learning algorithms")`
   - Assert the returned string is non-empty and contains expected terms

This test validates that:
- The hybrid search pipeline works end-to-end (E2E-02)
- KnowledgeTools returns content from hybrid results (Plan 02 verification)
- RRF scores are present and ordered correctly
</action>
  <verify>
    <automated>mvn test -Dtest=E2EIntegrationTests#test05_hybridSearchReturnsRerankedResults -q</automated>
  </verify>
  <done>Tests verify hybrid search returns results with RRF scores, and KnowledgeTools integration returns non-empty content</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Test -> DocumentFacadeService | Tests use MockMultipartFile with controlled content — no injection surface |
| Test -> Database | Tests use real DB — must clean up after themselves to avoid pollution |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-5-05 | Denial of Service | Test data pollution | mitigate | @AfterEach cleanup deletes test KB documents and KB record; use UUID-based KB IDs for uniqueness |
| T-5-06 | Information Exposure | Test files on disk | accept | MockMultipartFile uses in-memory byte arrays, no temp files written to disk |
</threat_model>

<verification>
- `mvn test -Dtest=E2EIntegrationTests -q` runs all 6 tests
- Each test uploads a file, processes it, and verifies search returns results
- Tests clean up after themselves in @AfterEach
- Hybrid search results include rrfScore > 0
- KnowledgeTools.knowledgeQuery returns non-empty content string
- Full suite: `mvn test -pl jchatmind` (includes existing tests)
</verification>

<success_criteria>
- E2E-01: TXT, PDF, DOCX, CSV files upload -> parse -> chunk -> index -> search successfully
- E2E-02: Hybrid search returns results with RRF scores; KnowledgeTools returns content from hybrid results
- Test class exists at `jchatmind/src/test/java/com/kama/jchatmind/integration/E2EIntegrationTests.java`
- Tests are repeatable (idempotent) with proper @BeforeEach/@AfterEach lifecycle
- All tests pass in an environment with PostgreSQL + Ollama running
</success_criteria>

<output>
After completion, create `.planning/phases/5/5-03-SUMMARY.md`
</output>
