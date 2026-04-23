# JChatMind RAG Module Optimization

## Project

JChatMind RAG Module Optimization — 优化 RAG 模块，支持多格式文档解析、语义切分、混合检索（BM25 + 向量）和 rerank。

## GSD Workflow Enforcement

This project uses GSD (Get Shit Done) for structured development. All work follows these rules:

- **Always check ROADMAP.md** before starting work to understand the current phase
- **Phase plans live in `.planning/phases/N/PLAN.md`** — read before implementing
- **Atomic commits** — commit after each logical unit of work
- **No speculative features** — only implement what the phase plan specifies
- **Preserve existing patterns** — match the existing codebase style and architecture
- **Never delete .planning/ files** — they are the source of truth for what to build

## Project Structure

```
jchatmind/src/main/java/com/kama/jchatmind/
  agent/          # Agent core (Think-Execute loop)
  service/        # Business logic — RAG services here
  controller/     # REST API endpoints
  model/          # DTO, Entity, Request, Response
  mapper/         # MyBatis mapper interfaces
  config/         # Spring configuration
jchatmind/src/main/resources/
  mapper/         # MyBatis XML SQL files
  application.yaml # App configuration
ui/               # React frontend
```

## Current Phase

**Phase 1: Multi-Format Document Parser** — Support TXT, DOCX, PDF, Excel/CSV parsing

See `.planning/ROADMAP.md` for full roadmap and `.planning/REQUIREMENTS.md` for detailed requirements.
