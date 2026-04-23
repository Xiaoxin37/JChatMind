---
phase: 2
name: Semantic Chunking
---

# Phase 2 Context: Semantic Chunking

## Decisions

### 1. Chunk Max Size: 512 tokens

**Decision:** Each chunk must not exceed 512 tokens.

**Why:** Matches bge-m3 embedding model's typical effective range. Smaller chunks produce more precise embeddings.

**How to apply:** During chunking, measure accumulated text in tokens (or approximate via character count: ~4 chars per token for English, ~1.5 chars per token for Chinese). Split when approaching 512-token limit.

### 2. Split Rule: 段落优先，句子兜底

**Decision:** Split by paragraph boundary (double newline `\n\n`) first. If a single paragraph exceeds 512 tokens, fall back to sentence-level splitting (period/question mark/exclamation mark as boundary).

**Why:** Paragraphs are natural semantic units. Most paragraphs won't exceed 512 tokens. Sentence-level splitting is a safety net for unusually long paragraphs (e.g., legal documents, continuous tables).

**How to apply:**
1. First split ParsedDocument content by `\n\n` into paragraph chunks
2. For any paragraph > 512 tokens, split by sentence boundary regex: `[。！？.\!?]+`
3. Accumulate sentences into chunks until approaching 512-token limit

### 3. Overlap: 15% overlap

**Decision:** Each chunk retains ~15% of its trailing content as the beginning of the next chunk.

**Why:** Prevents semantic断裂 at chunk boundaries. A concept that spans two chunks will have partial representation in both, improving retrieval recall.

**How to apply:** When splitting, calculate overlap as ~15% of chunk size (~77 tokens for 512-token max). Append the last ~77 tokens of chunk N to the beginning of chunk N+1. Overlap applies at both paragraph and sentence split levels.

### 4. Metadata: 存储完整标题路径

**Decision:** Store the full title hierarchy path in chunk metadata (e.g., `"Chapter 1 > Section 2 > Subsection A"`).

**Why:** Enables retrieval results to show source context to the user. Supports filtering by document section in future phases.

**How to apply:** Serialize the `ParsedDocument.hierarchy` list into the chunk's metadata field. Use JSON string format for database storage.

## Prior Decisions (from Phase 1)

- `ParsedDocument` is the unified input format with fields: title, content, hierarchy, sourceFormat
- Each ParsedDocument currently creates 1 chunk — Phase 2 splits long content further
- bge-m3 model via Ollama (`http://localhost:11434`) for embeddings
- Chunk entity: `ChunkBgeM3` with fields: kbId, docId, content, metadata, embedding
- Embedding is done via `RagService.embed(title)` — Phase 2 should embed chunk content (not just title)
- Database mapper: `ChunkBgeM3Mapper` with MyBatis XML

## Scope Boundaries

**In scope:**
- Split ParsedDocument content into multiple chunks by semantic boundaries
- Configurable 512-token max chunk size
- Paragraph-first, sentence-fallback splitting
- 15% overlap between chunks
- Full title path in chunk metadata

**Out of scope (deferred):**
- Dynamic chunk size configuration UI (deferred to future)
- NLP library integration for sentence boundary (keep regex-based)
- BM25 indexing (Phase 3)
- Reranking (Phase 4)

## Canonical refs

- `.planning/ROADMAP.md` — Phase 2 goal and success criteria
- `.planning/phases/1/CONTEXT.md` — ParsedDocument structure, prior decisions
- `jchatmind/src/main/java/com/kama/jchatmind/model/entity/ChunkBgeM3.java` — chunk entity
- `jchatmind/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java` — embedding service
- `jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImpl.java` — upload flow (chunk creation point)
