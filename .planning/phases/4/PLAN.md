---
phase: 4
plan: reranking
type: implementation
wave: 1
depends_on: []
files_modified:
  - jchatmind/src/main/java/com/kama/jchatmind/service/RagService.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/HybridSearchService.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/impl/HybridSearchServiceImpl.java
  - jchatmind/src/main/resources/application.yaml
autonomous: true
requirements: RERANK-01, RERANK-02, RERANK-03
---

# Phase 4 Plan: Reranking

**Goal:** 引入 bge-reranker 对混合检索结果进行重排序

## Implementation Tasks

### Task 1: Add `rerank` Method to `RagService`

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/RagService.java`

Add method:
```java
List<RerankResult> rerank(String query, List<String> documents);

class RerankResult {
    int originalIndex;
    double score;
}
```

### Task 2: Implement `rerank` in `RagServiceImpl`

**Key logic:**
1. For each document, format as `{query}\n\n{document_content}`
2. Call Ollama `/api/embeddings` with model `bge-reranker-v2-m3` (or configurable model name)
3. Compute cosine similarity between query embedding and each (query, doc) pair embedding
4. Rank by similarity score descending
5. Add timeout: 5 seconds per embed call
6. Degradation: if Ollama call fails, return documents in original order with log warning

**Why cosine similarity:** Ollama doesn't have a native rerank API. The Embed API produces fixed-length vectors — cosine similarity between query and (query, doc) pair embeddings serves as the rerank score.

### Task 3: Add Reranking Config to `application.yaml`

```yaml
rag:
  reranking:
    enabled: true
    model: bge-reranker-v2-m3
    top-n: 20        # candidates to rerank
    timeout-seconds: 5
```

### Task 4: Wire Rerank into `HybridSearchServiceImpl`

**Changes:**
- Inject `RagService` for reranking
- After RRF fusion, take top-N (default 20) candidates
- If `rag.reranking.enabled` is true: call `ragService.rerank(query, candidates)`
- Return top-K final results after reranking

### Task 5: Update `HybridSearchService` Interface

**Changes:**
- `search` method now takes an additional `rerankEnabled` flag or reads from config
- Return type stays the same but now includes rerank scores when enabled

## Task Dependencies

```
Task 1 (interface) ──────┐
                          ├── Task 4 (wire into hybrid)
Task 2 (impl) ────────────┤
Task 3 (config) ──────────┘

Task 5 (interface update) ── after Task 4
```

## Verification Criteria

1. Rerank calls Ollama Embed API with (query, doc) pairs
2. Cosine similarity scores correctly rank documents
3. Top-20 candidates reranked to final top-5
4. `rag.reranking.enabled: false` skips rerank, returns hybrid results
5. If Ollama is unavailable, rerank degrades gracefully (logs warning, returns hybrid results)
6. Timeout of 5 seconds prevents hanging on slow responses
