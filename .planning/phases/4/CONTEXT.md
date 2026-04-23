---
phase: 4
name: Reranking
---

# Phase 4 Context: Reranking

## Decisions

### 1. Reranker Model: Ollama Embed API as Reranker

**Decision:** Use Ollama's Embed API with `qllama/bge-reranker-v2-m3` model to generate (query, doc) pair embeddings, then compute cosine similarity as the rerank score.

**Why:** Ollama doesn't have a native rerank API. Using Embed API with cosine similarity is a practical workaround that avoids adding a separate reranking service. The bge-reranker-v2-m3 model is designed for cross-lingual reranking.

**How to apply:** Add a new method `rerank(query, documents)` to `RagService` that formats each (query, doc) pair, calls Ollama embed API, and ranks by cosine similarity.

### 2. Rerank Only Top-20 Candidates

**Decision:** Only rerank the top-20 candidates from hybrid search, not all chunks.

**Why:** Reranking is expensive (Ollama embed call per document). Top-20 provides enough candidate diversity while keeping latency manageable (~20 API calls per query).

**How to apply:** `HybridSearchService` returns top-20 after RRF, then reranker filters to final top-K (default 5).

### 3. Rerank Toggle: application.yaml Config Switch

**Decision:** Rerank can be toggled on/off via `rag.reranking.enabled: true/false` in application.yaml.

**Why:** During development/debugging, skipping rerank saves time. In production, rerank is enabled for better quality.

**How to apply:** Add `rag.reranking.enabled: true` config. Check in `RagService.rerank()` — if disabled, return input documents unchanged.

### 4. Timeout and Degradation: Skip Rerank if Ollama Unavailable

**Decision:** If Ollama model is not deployed or times out, skip rerank and return hybrid search results as-is.

**Why:** Rerank should improve quality, not block the pipeline. Graceful degradation is better than failure.

**How to apply:** Wrap rerank calls in try-catch with timeout. On failure, log warning and return original hybrid results.

## Prior Decisions (from Phase 1-3)

- HybridSearchService returns RRF-fused results (BM25 + vector)
- Default top-K after RRF is 5, but rerank needs top-20 input
- Ollama runs at `http://localhost:11434`
- bge-m3 model already used for embeddings

## Scope Boundaries

**In scope:**
- Ollama Embed API call for (query, doc) pair embeddings
- Cosine similarity scoring for reranking
- Top-20 candidate reranking to final top-K
- Configurable enabled/disabled switch
- Timeout and degradation strategy

**Out of scope (deferred):**
- Dedicated rerank API server (e.g., TEI, Jina Reranker)
- Batch reranking optimization
- Rerank model fine-tuning

## Canonical refs

- `.planning/ROADMAP.md` — Phase 4 goal and success criteria
- `jchatmind/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java` — Ollama API usage
- `jchatmind/src/main/java/com/kama/jchatmind/service/impl/HybridSearchServiceImpl.java` — hybrid search output
