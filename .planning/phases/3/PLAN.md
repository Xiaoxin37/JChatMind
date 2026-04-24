---
phase: 3
plan: bm25-hybrid-search
type: implementation
wave: 1
depends_on: []
files_modified:
  - jchatmind/pom.xml
  - jchatmind/src/main/java/com/kama/jchatmind/service/ChunkBgeM3IndexService.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/impl/ChunkBgeM3IndexServiceImpl.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/HybridSearchService.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/impl/HybridSearchServiceImpl.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/RagService.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImpl.java
  - jchatmind/src/main/resources/application.yaml
autonomous: true
requirements: BM25-01, BM25-02, BM25-03, HYBRID-01, HYBRID-02, HYBRID-03
---

# Phase 3 Plan: BM25 + Hybrid Search

**Goal:** 新增 BM25 关键词检索，实现 BM25 + 向量的混合检索与 RRF 融合

## Implementation Tasks

### Task 1: Add Lucene Dependencies to pom.xml

**Add after existing Tika dependencies:**
```xml
<!-- Lucene for BM25 full-text indexing -->
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-core</artifactId>
    <version>9.12.0</version>
</dependency>
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-analysis-common</artifactId>
    <version>9.12.0</version>
</dependency>
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-queryparser</artifactId>
    <version>9.12.0</version>
</dependency>
```

### Task 2: Create `ChunkBgeM3IndexService.java` Interface

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/ChunkBgeM3IndexService.java`

```java
public interface ChunkBgeM3IndexService {
    void indexChunk(String chunkId, String docId, String content);
    void deleteByDocId(String docId);
    List<Bm25Result> search(String query, int topK);
    void initializeIndex();

    class Bm25Result {
        String chunkId;
        double score;
    }
}
```

### Task 3: Create `ChunkBgeM3IndexServiceImpl.java`

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/impl/ChunkBgeM3IndexServiceImpl.java`

**Key responsibilities:**
- `@PostConstruct initializeIndex()`: Opens or creates Lucene index at `./data/bm25-index`
- `indexChunk(chunkId, docId, content)`: Creates Lucene document with BM25-analyzed content field
- `deleteByDocId(docId)`: Deletes all Lucene documents matching docId
- `search(query, topK)`: Parses query via `StandardAnalyzer`, searches with `BM25Similarity`, returns scored results

### Task 4: Update `RagService.similaritySearch` to Accept topK Parameter

**Changes:**
- Add overload: `List<String> similaritySearch(String kbId, String query, int topK)`
- Keep existing `similaritySearch(kbId, title)` for backward compat — it calls the new method with topK=3
- Embed the query text (not title) for search

### Task 5: Create `HybridSearchService.java` Interface

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/HybridSearchService.java`

```java
public interface HybridSearchService {
    List<HybridResult> search(String kbId, String query, int topK);

    class HybridResult {
        String chunkId;
        String content;
        String metadata;
        double rrfScore;
    }
}
```

### Task 6: Create `HybridSearchServiceImpl.java`

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/impl/HybridSearchServiceImpl.java`

**RRF Fusion algorithm:**
```
For each BM25 result at rank r_bm25: score += 1 / (60 + r_bm25)
For each Vector result at rank r_vec: score += 1 / (60 + r_vec)
Sort by combined score descending, return top-K
```

**Flow:**
1. Call `ChunkBgeM3IndexService.search(query, bm25TopK)` → BM25 results
2. Embed query via `ragService.embed(query)` → vector
3. Call `ChunkBgeM3Mapper.similaritySearch(kbId, vector, vectorTopK)` → vector results
4. RRF fusion: combine both result lists by chunkId
5. Sort by RRF score, return top-K

### Task 7: Add Configuration to `application.yaml`

```yaml
rag:
  retrieval:
    top-k: 5
    bm25-top-k: 20
    vector-top-k: 20
    rrf-k: 60
  chunking:
    max-tokens: 512
    overlap-ratio: 0.15
```

### Task 8: Wire ChunkingService Index into Upload Flow

**In `DocumentFacadeServiceImpl.processDocument`:**
- After each chunk is inserted into DB, call `chunkBgeM3IndexService.indexChunk(chunkId, docId, content)`

### Task 9: Wire Delete Sync into `DocumentFacadeServiceImpl.deleteDocument`

- After deleting DB records for a docId, call `chunkBgeM3IndexService.deleteByDocId(documentId)`

## Task Dependencies

```
Task 1 (Lucene deps) ──────┐
                            ├── Task 3 (Index Service Impl)
Task 2 (Index Interface) ───┘

Task 4 (RagService topK) ───┐
                             ├── Task 6 (Hybrid Search)
Task 3 (Index Service) ──────┤
Task 5 (Hybrid Interface) ───┘

Task 8 (Upload wiring) ─── depends on Task 3
Task 9 (Delete wiring) ─── depends on Task 3
Task 7 (config) ────────── independent
```

## Verification Criteria

1. Inserting a chunk automatically adds it to Lucene BM25 index
2. BM25 search returns results ranked by relevance score
3. Hybrid search combines BM25 + vector results via RRF
4. RRF score formula: `1 / (60 + rank)` applied correctly
5. Document deletion removes both DB records and Lucene index entries
6. Top-K configurable via application.yaml
7. Existing pure-vector search still works (backward compat)
