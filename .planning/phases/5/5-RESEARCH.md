# Phase 5: End-to-End Integration - Research

**Researched:** 2026-04-23
**Domain:** Spring Boot agent wiring, pgvector cosine migration, BM25 Lucene index, integration testing
**Confidence:** HIGH

## Summary

Phase 5 has a well-defined scope: wire `HybridSearchService` into the Agent's knowledge retrieval flow, migrate pgvector from L2 to cosine distance, and write E2E integration tests. All Phase 1-4 components are already implemented and production-ready -- the BM25 Lucene index, RRF fusion, reranking via Ollama Embed API, and multi-format document parsing with semantic chunking all exist in the codebase. The primary work is a surgical replacement in `KnowledgeTools.java`, a SQL operator change in two files, and integration test scaffolding.

**Primary recommendation:** Replace `KnowledgeTools` dependency from `RagService` to `HybridSearchService`, change pgvector operator from `<->` (L2) to `<=>` (cosine) in both `ChunkBgeM3Mapper.xml` and `jchatmind.sql`, write `@SpringBootTest` integration tests that exercise upload->parse->chunk->embed->index->search->rerank.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Agent Think-Execute loop | API / Backend | -- | Server-side Spring Boot agent orchestrating tool calls |
| Knowledge retrieval (hybrid search) | API / Backend | Database / Storage | HybridSearchService runs on server, queries PostgreSQL + Lucene index |
| BM25 full-text indexing | API / Backend | -- | Lucene embedded in JVM, filesystem-based index |
| Vector similarity search | Database / Storage | API / Backend | pgvector in PostgreSQL, Java builds query vectors via Ollama |
| Reranking | API / Backend | -- | Java calls Ollama Embed API, computes cosine similarity locally |
| Document parsing | API / Backend | -- | Apache Tika / POI in Spring Boot service layer |
| Semantic chunking | API / Backend | -- | ChunkingServiceImpl in service layer |
| Integration tests | API / Backend | Database / Storage | @SpringBootTest with embedded Spring context, requires PostgreSQL + Ollama |

## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** In the Agent's knowledge-retrieval step, **directly replace** `RagService.similaritySearch()` with `HybridSearchService.search()`
- **D-02:** No configuration switch -- use HybridSearchService directly, no fallback to pure vector search (each sub-step already has its own degradation logic)
- **D-03:** Change pgvector index from `vector_l2_ops` to `vector_cosine_ops`, SQL operator from `<->` to `<=>`, matching bge-m3 normalized vector semantics
- **D-04:** Write Spring Boot `@SpringBootTest` integration tests that automate verification of each format file: upload -> parse -> chunk -> index -> search -> full flow

### Claude's Discretion

- Integration test case design and test data construction
- Database migration script approach (manual SQL vs Flyway/Liquibase)
- Exact code location in Agent flow where search call replacement occurs (confirmed via code reading)

### Deferred Ideas (OUT OF SCOPE)

- Configuration-level retrieval mode switching (hybrid/vector) -- user decided not to introduce in this phase
- Frontend upload status display -- ROADMAP.md marks as UI hint, but this phase focuses on backend integration tests

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Agent Think-Execute loop | API / Backend | -- | Server-side Spring Boot agent orchestrating tool calls |
| Knowledge retrieval (hybrid search) | API / Backend | Database / Storage | HybridSearchService runs on server, queries PostgreSQL + Lucene index |
| BM25 full-text indexing | API / Backend | -- | Lucene embedded in JVM, filesystem-based index |
| Vector similarity search | Database / Storage | API / Backend | pgvector in PostgreSQL, Java builds query vectors via Ollama Embed API |
| Reranking | API / Backend | -- | Java calls Ollama Embed API, computes cosine similarity locally |
| Document parsing | API / Backend | -- | Apache Tika / POI in Spring Boot service layer |
| Semantic chunking | API / Backend | -- | ChunkingServiceImpl in service layer |
| Integration tests | API / Backend | Database / Storage | @SpringBootTest with embedded Spring context, requires PostgreSQL + Ollama |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.8 (project) | Application framework | Existing project base |
| Spring AI | 1.1.0 (BOM) | LLM tool callbacks, chat orchestration | Existing project, provides `ToolCallback`, `MethodToolCallbackProvider` |
| pgvector | 0.5+ (PostgreSQL extension) | Vector similarity search | Existing vector store, only opclass change needed |
| Apache Lucene | 9.12.0 (project) | BM25 full-text indexing | Existing embedded BM25, already initialized |
| MyBatis | 3.0.3 (starter) | SQL mapping | Existing data access layer |
| JUnit Jupiter | 5.12.2 (from Spring Boot parent) | Integration test framework | Spring Boot 3.5.8 manages this version |
| Mockito | 5.17.0 (from Spring Boot parent) | Test mocking | Spring Boot 3.5.8 manages this version |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Apache Tika | 3.3.0 (project) | Multi-format document parsing | Already wired into DocumentParserService |
| Apache POI | 5.4.1 (project) | Excel handling | Already used for xlsx/csv |
| Flexmark | 0.64.8 (project) | Markdown parsing | Existing compatibility path |

**Installation:**
No new dependencies required. All needed libraries are already in `pom.xml`.

**Version verification:** Verified via `mvn dependency:list` against the project's Spring Boot 3.5.8 parent POM. Lucene 9.12.0, JUnit 5.12.2, Mockito 5.17.0 confirmed in resolved dependency tree.

## Architecture Patterns

### System Architecture Diagram

```
                    ┌─────────────────────────────────────────────────┐
                    │              Agent (JChatMind)                   │
                    │                                                  │
 User Query ───────►│  think() ────────► ChatClient (DeepSeek/Zhipu)  │
                    │                    │                             │
                    │                    ▼                             │
                    │              KnowledgeTools                      │
                    │              (REPLACE HERE)                      │
                    │                    │                             │
                    │    ┌───────────────┼───────────────┐             │
                    │    │  BEFORE:      │   AFTER:       │             │
                    │    │ RagService    │ HybridSearch   │             │
                    │    │ .similarity   │ Service.search │             │
                    │    │ Search()      │ ()             │             │
                    │    └───────────────┼───────────────┘             │
                    └────────────────────┼─────────────────────────────┘
                                         │
                      ┌──────────────────┼──────────────────┐
                      ▼                  ▼                   ▼
              ┌──────────────┐  ┌──────────────┐   ┌─────────────────┐
              │  BM25 Search │  │ Vector Search │   │    Rerank       │
              │  (Lucene)    │  │ (pgvector)    │   │  (Ollama Embed) │
              │              │  │               │   │                 │
              │ ./data/      │  │ PostgreSQL    │   │ http://         │
              │ bm25-index/  │  │ chunk_bge_m3  │   │ localhost:11434 │
              └──────┬───────┘  └───────┬───────┘   └────────┬────────┘
                     │                  │                     │
                     └────────┬─────────┘                     │
                              ▼                               │
                     ┌──────────────┐                         │
                     │ RRF Fusion   │────────────────────────►┤
                     │ (k=60)       │                         │
                     └──────────────┘                         │
                              ▼                               │
                     ┌──────────────┐                         │
                     │  Rerank      │◄────────────────────────┘
                     │  (cosine sim)│
                     └──────┬───────┘
                            ▼
                     Final top-K results
                     returned to Agent
```

### Recommended Project Structure
```
jchatmind/src/main/java/com/kama/jchatmind/
├── agent/
│   ├── tools/
│   │   └── KnowledgeTools.java       # MODIFY: replace RagService -> HybridSearchService
├── service/
│   ├── HybridSearchService.java      # EXISTING: interface
│   ├── RagService.java               # EXISTING: keep (used by HybridSearchService internally)
│   └── impl/
│       ├── HybridSearchServiceImpl.java  # EXISTING: full BM25+Vector+RRF+Rerank
│       └── RagServiceImpl.java           # EXISTING: embedding + rerank (still used)
├── mapper/
│   └── ChunkBgeM3Mapper.java         # EXISTING: no change to interface
└── model/
    └── entity/ChunkBgeM3.java        # EXISTING: no change

jchatmind/src/main/resources/
├── mapper/ChunkBgeM3Mapper.xml       # MODIFY: <-> to <=> in similaritySearch
└── application.yaml                  # EXISTING: all config keys present

jchatmind/src/test/java/com/kama/jchatmind/
└── integration/
    └── E2EIntegrationTests.java      # NEW: @SpringBootTest integration tests

jchatmind_assert/
└── jchatmind.sql                     # MODIFY: vector_l2_ops to vector_cosine_ops
```

### Pattern 1: Agent Tool Wiring via JChatMindFactory

**What:** Tools are registered in `JChatMindFactory.resolveRuntimeTools()` which fetches from `ToolFacadeService.getFixedTools()` and optional tools by name. `KnowledgeTools` is a `@Component` implementing `Tool`, converted to `ToolCallback` via `MethodToolCallbackProvider`.

**When to use:** This is the existing pattern -- all agent tools follow it.

**Key file:** `JChatMindFactory.java` lines 149-170 show tool resolution, lines 172-183 show callback building.

```java
// Source: JChatMindFactory.java, line 172
private List<ToolCallback> buildToolCallbacks(List<Tool> runtimeTools) {
    List<ToolCallback> callbacks = new ArrayList<>();
    for (Tool tool : runtimeTools) {
        Object target = resolveToolTarget(tool);
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(target)
                .build()
                .getToolCallbacks();
        callbacks.addAll(Arrays.asList(toolCallbacks));
    }
    return callbacks;
}
```

**Implication for Phase 5:** Since `KnowledgeTools` is a `@Component` and Spring auto-wires it, the replacement is a simple constructor-level change -- replace `RagService` with `HybridSearchService` in `KnowledgeTools`' constructor.

### Anti-Patterns to Avoid

- **Do NOT delete RagService:** HybridSearchServiceImpl depends on RagService for `embed()` and `rerank()`. Removing RagService would break the hybrid search chain.
- **Do NOT modify the HybridSearchServiceImpl interface or implementation:** It is already complete. The CONTEXT.md explicitly states "zero modification needed to access."
- **Do NOT add a config toggle for hybrid vs vector:** Decision D-02 explicitly rejects this. Each sub-step already has its own degradation logic (e.g., BM25 failure -> vector-only, rerank failure -> RRF results).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Test framework | Custom test runner | JUnit 5 + @SpringBootTest | Spring Boot parent already manages versions, provides auto-configuration for tests |
| Vector distance operator | Custom cosine function in SQL | pgvector `<=>` operator | pgvector is optimized, uses index; custom function would be O(n) scan |
| BM25 scoring | Custom keyword matching | Lucene 9.12.0 (already embedded) | Lucene handles tokenization, IDF, field weighting, phrase queries |
| RRF fusion | Custom weighted sum | Standard RRF with k=60 | RRF with k=60 is the IR community standard, proven across competitions |
| Embedding API client | Raw HTTP client | Ollama Embed API via RagService (existing) | Already handles WebClient setup, timeout, error handling |

**Key insight:** Phase 5 is an integration phase, not a build phase. All components exist. The only changes are: (1) wire the right service, (2) fix the distance operator, (3) verify the pipeline.

## Common Pitfalls

### Pitfall 1: Ollama URL hardcoded in RagServiceImpl
**What goes wrong:** `RagServiceImpl.java:32` hardcodes `baseUrl("http://localhost:11434")` instead of reading from `application.yaml`. No `ollama.url` or similar config key exists.
**Why it happens:** The constructor `public RagServiceImpl(WebClient.Builder builder, ChunkBgeM3Mapper chunkBgeM3Mapper)` does not accept an `@Value` for the URL.
**How to avoid:** This is not a Phase 5 blocker (the URL works if Ollama runs locally), but should be flagged as a code smell. If the planer decides to fix it, it requires a RagServiceImpl constructor change.
**Warning signs:** Spring Boot fails to start if Ollama is not on localhost:11434.

### Pitfall 2: pgvector IVFFlat index requires re-creation for opclass change
**What goes wrong:** Changing `<->` to `<=>` in the SQL query alone is not sufficient. The existing `CREATE INDEX ... USING ivfflat (embedding vector_l2_ops)` must be dropped and recreated with `vector_cosine_ops`, otherwise the index is not used for cosine queries and PostgreSQL falls back to sequential scan.
**Why it happens:** pgvector index opclasses are distance-specific. `vector_l2_ops` indexes do not accelerate `<=>` queries.
**How to avoid:** Migration SQL must `DROP INDEX idx_chunk_embedding` then `CREATE INDEX ... USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)`.
**Warning signs:** EXPLAIN ANALYZE shows Seq Scan instead of Index Scan on chunk_bge_m3 queries.

### Pitfall 3: BM25 index not rebuilt after existing data migration
**What goes wrong:** If the database already has chunks from Phase 1-4 runs, those chunks were indexed into Lucene via `chunkBgeM3IndexService.indexChunk()`. However, if the Lucene index files (`./data/bm25-index/`) are missing or stale (e.g., deleted, or from a different run), BM25 search returns empty results.
**Why it happens:** The Lucene index is filesystem-based, not database-backed. It must be rebuilt if the index directory is cleaned.
**How to avoid:** For integration tests, ensure a clean index state. If testing against existing data, verify the index directory exists and contains Lucene segments.
**Warning signs:** BM25 search returns 0 results while vector search returns results for the same query.

### Pitfall 4: `KnowledgeTools` parameter name mismatch
**What goes wrong:** The `KnowledgeTools.knowledgeQuery()` method at line 37 has a parameter named `kbsId` (not `kbId`). This is an internal naming inconsistency that does not affect functionality, but should be noted.
**Why it happens:** Typo in the original implementation.
**How to avoid:** The planner should be aware that the parameter is `kbsId` in the method signature, but internally it passes `kbId` to `similaritySearch(kbsId, query)`.
**Warning signs:** None at runtime -- just a naming inconsistency.

## Code Examples

Verified patterns from codebase:

### Replace RagService with HybridSearchService in KnowledgeTools

```java
// Source: jchatmind/src/main/java/com/kama/jchatmind/agent/tools/KnowledgeTools.java (current, lines 1-40)
// CHANGE: Replace RagService with HybridSearchService in constructor and call site

// BEFORE:
private final RagService ragService;
public KnowledgeTools(RagService ragService) {
    this.ragService = ragService;
}
public String knowledgeQuery(String kbsId, String query) {
    List<String> strings = ragService.similaritySearch(kbsId, query);
    return String.join("\n", strings);
}

// AFTER:
private final HybridSearchService hybridSearchService;
public KnowledgeTools(HybridSearchService hybridSearchService) {
    this.hybridSearchService = hybridSearchService;
}
public String knowledgeQuery(String kbsId, String query) {
    // HybridSearchService.search() returns List<HybridResult> with content field
    List<HybridSearchService.HybridResult> results = hybridSearchService.search(kbsId, query, 5);
    return results.stream()
            .map(HybridSearchService.HybridResult::content)
            .collect(Collectors.joining("\n"));
}
```

### pgvector SQL migration (L2 -> cosine)

```sql
-- Source: verified against pgvector official docs (Context7: /pgvector/pgvector)
-- Step 1: Drop existing L2 index
DROP INDEX IF EXISTS idx_chunk_embedding;

-- Step 2: Recreate with cosine opclass
CREATE INDEX idx_chunk_embedding
ON chunk_bge_m3
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);

-- Step 3: Update ChunkBgeM3Mapper.xml similaritySearch query
-- Change: ORDER BY embedding <-> #{vectorLiteral}::vector
-- To:     ORDER BY embedding <=> #{vectorLiteral}::vector
```

### Integration test scaffold

```java
// Source: JchatmindApplicationTests.java (existing pattern) + Spring Boot 3.5.8
package com.kama.jchatmind.integration;

import com.kama.jchatmind.service.HybridSearchService;
import com.kama.jchatmind.service.DocumentFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class E2EIntegrationTests {

    @Autowired private DocumentFacadeService documentFacadeService;
    @Autowired private HybridSearchService hybridSearchService;

    @Test
    void fullPipeline_txtFile_uploadParseChunkEmbedSearch() {
        // Step 1: Create KB (helper method or use existing)
        String kbId = "test-kb-id";

        // Step 2: Upload and process a .txt file
        MockMultipartFile txtFile = new MockMultipartFile(
            "file", "test.txt", "text/plain",
            "Spring Boot is a Java framework for building microservices.".getBytes()
        );
        var response = documentFacadeService.uploadDocument(kbId, txtFile);
        assertNotNull(response.getDocumentId());

        // Step 3: Search via hybrid search
        var results = hybridSearchService.search(kbId, "Java framework microservices", 5);
        assertFalse(results.isEmpty(), "Should find the uploaded content via hybrid search");
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `<->` (L2 distance) for bge-m3 vectors | `<=>` (cosine distance) for bge-m3 vectors | Phase 5 | bge-m3 outputs L2-normalized vectors; L2 distance on normalized vectors approximates cosine but is semantically incorrect. Cosine distance is the proper metric. |
| `vector_l2_ops` IVFFlat index | `vector_cosine_ops` IVFFlat index | Phase 5 | Index must match query operator for index usage. Mismatched opclass causes full table scan. |
| `RagService.similaritySearch` in Agent | `HybridSearchService.search` in Agent | Phase 5 | Single vector retrieval -> BM25 + Vector + RRF + Rerank pipeline. Significantly improved retrieval quality. |

**Deprecated/outdated:**
- **L2 distance on normalized vectors**: While mathematically L2 on unit vectors equals `sqrt(2 - 2*cos(theta))`, using `<=>` is semantically correct and ensures the pgvector index is utilized.
- **Hardcoded top-3 in similaritySearch**: The old `RagService.similaritySearch(kbId, title)` defaulted to top-3. HybridSearchService uses configurable `topK` via `${rag.retrieval.top-k:5}`.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `KnowledgeTools` is the ONLY call site for `RagService.similaritySearch` in the Agent flow | Agent flow wiring | If other call sites exist, they would remain on pure vector search, creating inconsistent retrieval |
| A2 | Ollama is expected to be running on localhost:11434 at runtime | Environment | If Ollama runs elsewhere, all embedding/rerank calls fail |
| A3 | The Lucene BM25 index at `./data/bm25-index/` is shared across all KBs (no per-KB index partitioning) | BM25 index persistence | BM25 search returns results across all KBs; filtering by kbId happens only in the vector search step |

## Open Questions

1. **What is the exact test KB ID strategy for integration tests?**
   - What we know: `knowledge_base` table has UUID primary keys, tests need a KB to upload into
   - What's unclear: Should tests create a new KB via `KnowledgeBaseFacadeService`, or use a hardcoded test KB?
   - Recommendation: Create a test KB in `@BeforeEach` and clean up in `@AfterEach` for test isolation

2. **Should the Ollama URL be externalized to application.yaml config?**
   - What we know: Currently hardcoded to `http://localhost:11434` in `RagServiceImpl` constructor
   - What's unclear: Is this intentional (development-only) or should it be configurable?
   - Recommendation: Out of scope for Phase 5 (D-02 says no config switch). Flag as Phase 6 improvement.

3. **Does the project use Flyway/Liquibase for migrations?**
   - What we know: No `db/migration` directory, no Flyway/Liquibase dependency in pom.xml
   - What's unclear: Manual SQL scripts are the established pattern
   - Recommendation: Continue manual SQL migration pattern. Add a `migrations/` directory or include in `jchatmind_assert/`.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| PostgreSQL | pgvector queries, chunk storage | To be verified | Needs `pgvector` extension | None -- blocking |
| Ollama | Embedding API (bge-m3), reranker (bge-reranker-v2-m3) | To be verified | Needs `bge-m3` and `bge-reranker-v2-m3` models | Skip rerank, vector-only degrades gracefully |
| Java 17 | Spring Boot 3.5.8 runtime | To be verified | `java.version=17` in pom.xml | None -- blocking |
| Maven | Build + test execution | To be verified | Project uses Maven | None -- blocking |
| Lucene BM25 index dir `./data/bm25-index/` | BM25 search | To be verified | Created at startup by `@PostConstruct` | Auto-created if parent dir exists |

**Missing dependencies with no fallback:**
- PostgreSQL with pgvector extension -- blocking for all vector operations
- Ollama with `bge-m3` model -- blocking for embedding and reranking

**Missing dependencies with fallback:**
- BM25 Lucene index files -- if missing, BM25 search returns empty but vector search still works (HybridSearchService has built-in degradation)

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit Jupiter 5.12.2 + Spring Boot Test |
| Config file | None -- uses Spring Boot parent defaults |
| Quick run command | `mvn test -Dtest=E2EIntegrationTests -x` |
| Full suite command | `mvn test -pl jchatmind` |

### Phase Requirements to Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| E2E-01 | Upload non-MD file -> parse -> chunk -> embed -> index | Integration | `mvn test -Dtest=E2EIntegrationTests#fullPipeline_* -x` | Wave 0 |
| E2E-02 | Agent query -> hybrid search + rerank -> results | Integration | `mvn test -Dtest=E2EIntegrationTests#agentQueryReturnsResults -x` | Wave 0 |
| E2E-03 | DB schema compatibility (BM25 index, chunk metadata) | Manual/SQL | `psql -f jchatmind_assert/jchatmind.sql` | Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=E2EIntegrationTests -x` (quick, runs specific integration tests)
- **Per wave merge:** `mvn test -pl jchatmind` (full suite including existing unit tests)
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `jchatmind/src/test/java/com/kama/jchatmind/integration/E2EIntegrationTests.java` -- covers E2E-01, E2E-02
- [ ] `jchatmind/src/test/java/com/kama/jchatmind/integration/package-info.java` -- package info
- [ ] Test KB helper utility or `@BeforeEach` setup for test data
- [ ] SQL migration script for vector_l2_ops -> vector_cosine_ops (E2E-03)
- [ ] `jchatmind/src/test/java/com/kama/jchatmind/JChatMindTests.java` -- exists but empty, may need augmentation

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | N/A -- this phase has no auth changes |
| V3 Session Management | No | N/A -- no session changes |
| V4 Access Control | No | N/A -- no access control changes |
| V5 Input Validation | Yes | Existing Spring Boot validation; KB ID is UUID-validated by PostgreSQL |
| V6 Cryptography | No | N/A -- no cryptographic changes |

### Known Threat Patterns for Spring Boot + PostgreSQL

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| SQL injection via MyBatis | Tampering | Parameterized queries via `#{}` syntax (already used) |
| File upload abuse | Tampering | Existing `getFileType()` + `isSupportedFormat()` whitelist |
| Ollama SSRF | Tampering | Ollama URL is hardcoded to localhost, not user-controllable |

## Sources

### Primary (HIGH confidence)
- Context7: `/pgvector/pgvector` -- cosine distance operator `<=>`, `vector_cosine_ops` index, IVFFlat with cosine
- Codebase: `KnowledgeTools.java` (line 37) -- exact call site for `ragService.similaritySearch(kbsId, query)`
- Codebase: `HybridSearchServiceImpl.java` -- complete implementation verified, depends on `ChunkBgeM3IndexService`, `RagService`, `ChunkBgeM3Mapper`
- Codebase: `ChunkBgeM3IndexServiceImpl.java` -- Lucene BM25 with `@PostConstruct` initialization, `FSDirectory` at `./data/bm25-index/`
- Codebase: `ChunkBgeM3Mapper.xml` (line 110) -- current `ORDER BY embedding <-> #{vectorLiteral}::vector`
- Codebase: `jchatmind_assert/jchatmind.sql` (line 86) -- current `USING ivfflat (embedding vector_l2_ops)`
- Codebase: `application.yaml` -- all `rag.retrieval.*`, `rag.reranking.*` config keys present
- Codebase: `pom.xml` -- Spring Boot 3.5.8, Spring AI BOM 1.1.0, Lucene 9.12.0, JUnit 5.12.2, Mockito 5.17.0
- Codebase: `JchatmindApplicationTests.java` -- existing `@SpringBootTest` pattern

### Secondary (MEDIUM confidence)
- WebSearch: pgvector distance operators documentation (multiple sources confirm `<=>` = cosine, `<->` = L2)
- WebSearch: IVFFlat index opclass matching requirement (index must use same opclass as query operator)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- verified via `mvn dependency:list` and codebase inspection
- Architecture: HIGH -- exact call sites and dependencies traced in source code
- Pitfalls: HIGH -- verified by reading actual implementation code
- Environment: MEDIUM -- PostgreSQL and Ollama availability need runtime verification

**Research date:** 2026-04-23
**Valid until:** 2026-05-23 (stable domain, no fast-moving libraries)
