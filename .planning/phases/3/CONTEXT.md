---
phase: 3
name: BM25 + Hybrid Search
---

# Phase 3 Context: BM25 + Hybrid Search

## Decisions

### 1. BM25 Storage: Lucene 本地文件系统索引

**Decision:** Use embedded Apache Lucene for BM25 full-text indexing. Index stored on local filesystem alongside the application data directory.

**Why:** No external dependencies (no Elasticsearch, no PostgreSQL extensions). Lucene is battle-tested for BM25 scoring and integrates directly into Java. Simple to manage — index loads on app startup.

**How to apply:** Add `lucene-core` + `lucene-analyzers-common` dependencies. Create a Lucene `Directory` pointing to `./data/bm25-index`. Index each chunk's content as a Lucene document with fields: chunkId, content, docId. Search using `BM25Similarity`.

### 2. Top-K: 5 (configurable)

**Decision:** Default top-5 results returned after RRF fusion. Configurable via application.yaml.

**Why:** 5 is enough for most knowledge retrieval scenarios. Not too many to overwhelm the LLM context window, not too few to miss relevant chunks.

**How to apply:** Add `rag.retrieval.top-k: 5` to application.yaml. Used by the hybrid search service to limit RRF results.

### 3. Config: application.yaml

**Decision:** All BM25 and RRF parameters managed via application.yaml configuration.

**Why:** Simple, version-controlled, no database migration needed. Parameters change rarely (during tuning), not at runtime.

**How to apply:** Configuration keys:
- `rag.retrieval.top-k: 5` — final results count
- `rag.retrieval.bm25-top-k: 20` — BM25 candidate count (larger for RRF)
- `rag.retrieval.vector-top-k: 20` — vector candidate count (larger for RRF)
- `rag.retrieval.rrf-k: 60` — RRF constant

### 4. Delete Sync: 文档删除时同步

**Decision:** When a document is deleted, its corresponding Lucene index entries must also be removed.

**Why:** Prevents stale results from BM25 search for deleted documents. Maintains index-document consistency.

**How to apply:** In `DocumentFacadeServiceImpl.deleteDocument`, after deleting DB records, also delete from Lucene index by docId field.

## Prior Decisions (from Phase 1 & 2)

- Chunks stored via `ChunkBgeM3` (kbId, docId, content, metadata, embedding)
- Each chunk's content is embedded via Ollama bge-m3
- `ChunkingService` splits ParsedDocument into multiple chunks (512-token max, 15% overlap)
- `RagService.similaritySearch` currently returns top-3 fixed vector results
- Lucene index path: `./data/bm25-index` (relative to app root)

## Scope Boundaries

**In scope:**
- Lucene BM25 index creation on chunk insert
- BM25 keyword search returning scored results
- Vector similarity search (existing, but top-K configurable)
- RRF fusion of BM25 + vector results (k=60)
- Index sync on document delete
- All parameters configurable via application.yaml

**Out of scope (deferred):**
- Reranking (Phase 4)
- Dynamic parameter changes at runtime
- BM25 index optimization/compaction
- Distributed index (single-node only for now)

## Canonical refs

- `.planning/ROADMAP.md` — Phase 3 goal and success criteria
- `.planning/phases/2/CONTEXT.md` — ChunkingService output, chunk structure
- `jchatmind/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java` — current vector search
- `jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImpl.java` — deleteDocument method
- `jchatmind/src/main/java/com/kama/jchatmind/mapper/ChunkBgeM3Mapper.java` — chunk DB operations
