---
phase: 3
plan: bm25-hybrid-search
type: implementation
tags: bm25, hybrid-search, rrf, lucene, rag
key-files:
  - jchatmind/src/main/java/com/kama/jchatmind/service/ChunkBgeM3IndexService.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/impl/HybridSearchServiceImpl.java
metrics:
  files_created: 4
  files_modified: 5
---

# BM25 + Hybrid Search — Summary

## What Was Built

Implemented a hybrid search system combining BM25 keyword search (via embedded Apache Lucene) with vector similarity search using RRF (Reciprocal Rank Fusion). Chunks are automatically indexed into Lucene on insert, and index entries are cleaned up on document deletion.

## Technical Approach

1. **Lucene 9.12** for BM25 full-text indexing with `BM25Similarity`, local filesystem storage
2. **ChunkBgeM3IndexServiceImpl** manages index lifecycle (`@PostConstruct` init, `@PreDestroy` close), CRUD operations
3. **HybridSearchServiceImpl** executes parallel BM25 + vector searches, fuses via RRF: `score(d) = sum(1 / (k + rank_i(d)))`, k=60
4. **RagService** updated with `similaritySearch(kbId, query, topK)` overload — backward compatible
5. **DocumentFacadeServiceImpl** wired: index on chunk insert, delete sync on document deletion
6. **Config**: `rag.retrieval.*` for top-k, bm25-top-k, vector-top-k, rrf-k, bm25-index-path

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| Task 1 | 470b2ec | Added Lucene 9.12 core + analysis + queryparser dependencies |
| Task 2 | 470b2ec | Created ChunkBgeM3IndexService interface |
| Task 3 | 470b2ec | Implemented Lucene-based BM25 index service |
| Task 4 | 470b2ec | Added topK parameter to RagService.similaritySearch |
| Task 5 | 470b2ec | Created HybridSearchService interface |
| Task 6 | 470b2ec | Implemented RRF fusion combining BM25 + vector |
| Task 7 | 470b2ec | Added retrieval config to application.yaml |
| Task 8 | 470b2ec | Wired BM25 indexing into upload flow |
| Task 9 | 470b2ec | Wired delete sync into deleteDocument |

## Self-Check

**PASSED** — All Phase 3 plan tasks implemented. Pre-existing project errors (Lombok/ChatMessage/JChatMind agent) are unrelated to Phase 3 scope.

## Deviations

None — implemented exactly as specified in PLAN.md.
