---
phase: 2
plan: semantic-chunking
type: implementation
wave: 1
depends_on: []
files_modified:
  - jchatmind/src/main/java/com/kama/jchatmind/service/ChunkingService.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/impl/ChunkingServiceImpl.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImpl.java
  - jchatmind/src/main/resources/application.yaml
autonomous: true
requirements: CHUNK-01, CHUNK-02, CHUNK-03, CHUNK-04
---

# Phase 2 Plan: Semantic Chunking

**Goal:** 基于语义边界的智能 chunk 切分，替代纯标题切分

## Implementation Tasks

### Task 1: Create `ChunkingService.java` Interface

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/ChunkingService.java`

```java
package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ParsedDocument;

import java.util.List;

/**
 * Semantic chunking service.
 * Splits ParsedDocument content into chunks by semantic boundaries.
 */
public interface ChunkingService {
    /**
     * Split a ParsedDocument into multiple chunks.
     *
     * @param doc       Source document
     * @param maxTokens Maximum tokens per chunk (default 512)
     * @return List of chunk content strings
     */
    List<String> chunk(ParsedDocument doc, int maxTokens);
}
```

### Task 2: Create `ChunkingServiceImpl.java`

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/impl/ChunkingServiceImpl.java`

**Key logic:**
1. **Configurable max tokens** — read from `application.yaml` as `rag.chunking.max-tokens`, default 512
2. **Overlap ratio** — read from `application.yaml` as `rag.chunking.overlap-ratio`, default 0.15 (15%)
3. **Split algorithm:**
   - Step 1: Split content by `\n\n` (paragraph boundary)
   - Step 2: For each paragraph, estimate token count (Chinese chars: ~1.5 chars/token, English: ~4 chars/token)
   - Step 3: Accumulate paragraphs into a chunk until approaching maxTokens
   - Step 4: If a single paragraph exceeds maxTokens, split it by sentence boundary regex `[。！？.!?]+`
   - Step 5: Apply 15% overlap — last ~15% of each chunk's text becomes prefix of next chunk
4. **Output:** List of chunk content strings, each within token limit

**Token estimation helper:**
```java
private int estimateTokens(String text) {
    int chineseChars = 0;
    int englishChars = 0;
    for (char c : text.toCharArray()) {
        if (c > 127) chineseChars++;  // Chinese/Unicode
        else englishChars++;
    }
    return chineseChars / 2 + englishChars / 4;  // ~2 chars/token CN, ~4 chars/token EN
}
```

**Why estimate instead of tiktoken:** Avoid adding OpenAI/tiktoken dependency. Estimation is accurate enough for bge-m3 chunking and keeps the project lightweight.

### Task 3: Add Chunking Configuration to `application.yaml`

**Add to existing config:**
```yaml
rag:
  chunking:
    max-tokens: 512
    overlap-ratio: 0.15
```

### Task 4: Update `DocumentFacadeServiceImpl.processDocument` to Use ChunkingService

**Changes:**
- Inject `ChunkingService` as constructor dependency
- In `processDocument`, replace the current "1 ParsedDocument → 1 chunk" logic with:
  ```java
  List<String> chunkContents = chunkingService.chunk(section, maxTokens);
  for (String chunkContent : chunkContents) {
      // embed chunkContent, create ChunkBgeM3, insert
  }
  ```
- Embed each chunk's content (not the title) via `ragService.embed(chunkContent)`
- Build metadata JSON string containing: full hierarchy path from ParsedDocument, sourceFormat, chunk index within document
- Update the metadata field on each ChunkBgeM3 entity with this JSON

**Metadata format:**
```json
{
  "hierarchy": ["Chapter 1", "Section 2"],
  "sourceFormat": "pdf",
  "chunkIndex": 0,
  "totalChunks": 3,
  "title": "PDF文档 - 段落 1"
}
```

### Task 5: Add `selectByDocId` to `ChunkBgeM3Mapper` (for future delete/update sync)

**File:** `jchatmind/src/main/java/com/kama/jchatmind/mapper/ChunkBgeM3Mapper.java`

```java
List<ChunkBgeM3> selectByDocId(@Param("docId") String docId);
```

**File:** `jchatmind/src/main/resources/mapper/ChunkBgeM3Mapper.xml`

Add corresponding `<select>` statement.

## Task Dependencies

```
Task 1 (interface) ─────┐
Task 2 (implementation) ─┤
                         ├── Task 4 (Facade integration)
Task 3 (config) ─────────┘

Task 5 (mapper) ───────── independent, can run anytime
```

**Execution order:**
1. Task 3 (config) — independent
2. Task 1-2 (ChunkingService) — after Task 3
3. Task 4 (Facade integration) — after Task 1-2
4. Task 5 (mapper) — independent

## Verification Criteria

After implementation:

1. A ParsedDocument with content > 512 tokens produces multiple chunks
2. No chunk exceeds approximately 512 tokens
3. Chunk boundaries fall at paragraph or sentence level, not mid-word
4. Overlap content exists between consecutive chunks
5. Each chunk's metadata contains hierarchy path, sourceFormat, chunkIndex
6. Short documents (< 512 tokens) produce a single chunk (no unnecessary splitting)
7. Existing Markdown upload still works — chunk count may increase but content preserved

## Scope Boundaries (from CONTEXT.md)

**In scope:**
- Split ParsedDocument content by semantic boundaries
- 512-token max chunk size
- Paragraph-first, sentence-fallback splitting
- 15% overlap between chunks
- Full title path in chunk metadata

**Out of scope (deferred):**
- Dynamic chunk size configuration UI
- NLP library integration for sentence boundary
- BM25 indexing (Phase 3)
- Reranking (Phase 4)
