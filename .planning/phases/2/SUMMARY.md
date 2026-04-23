---
phase: 2
plan: semantic-chunking
type: implementation
tags: chunking, semantic, rag
key-files:
  - jchatmind/src/main/java/com/kama/jchatmind/service/ChunkingService.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/impl/ChunkingServiceImpl.java
  - jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImpl.java
metrics:
  files_created: 2
  files_modified: 4
---

# Semantic Chunking — Summary

## What Was Built

Implemented a `ChunkingService` that splits `ParsedDocument` content into semantically-bounded chunks. Each document section is now split by paragraph boundaries first, with sentence-level fallback for long paragraphs. 15% overlap between consecutive chunks prevents semantic断裂.

## Technical Approach

1. **ChunkingService** interface with `chunk(ParsedDocument, maxTokens)` method
2. **ChunkingServiceImpl** with:
   - Token estimation: Chinese ~2 chars/token, English ~4 chars/token
   - Paragraph-first splitting (`\n\n`)
   - Sentence-fallback for long paragraphs (regex: `[。！？.!?]+`)
   - 15% configurable overlap between consecutive chunks
   - Good break point detection (sentence boundaries, then spaces)
3. **Config**: `rag.chunking.max-tokens: 512`, `rag.chunking.overlap-ratio: 0.15`
4. **DocumentFacadeServiceImpl** updated to use chunking service — embeds each chunk's full content (not just title), generates structured JSON metadata with hierarchy, sourceFormat, chunkIndex, totalChunks
5. **ChunkBgeM3Mapper** added `selectByDocId` for future delete/update sync

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| Task 1 | fb03145 | Created ChunkingService interface |
| Task 2 | fb03145 | Implemented ChunkingServiceImpl with paragraph-first splitting |
| Task 3 | fb03145 | Added chunking config to application.yaml |
| Task 4 | fb03145 | Updated DocumentFacadeServiceImpl to use ChunkingService |
| Task 5 | fb03145 | Added selectByDocId to ChunkBgeM3Mapper + XML |

## Self-Check

**PASSED** — All Phase 2 plan tasks implemented. Phase 2 files compile without errors. Pre-existing project errors (Lombok/ChatMessage/JChatMind agent) are unrelated to Phase 2 scope.

## Deviations

None — implemented exactly as specified in PLAN.md.
