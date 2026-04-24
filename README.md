# JChatMind

JChatMind is a full-stack Java AI Agent project built around three capabilities:

- agent loop orchestration
- tool calling
- retrieval-augmented generation (RAG)

It is not just a chat box over an LLM. The system lets an agent decide when to answer directly, when to call tools, and when to retrieve evidence from a knowledge base, then streams the execution process to the frontend in real time.

## What This Project Does

The project provides:

- a Spring Boot backend for agent runtime, document ingestion, retrieval, and SSE streaming
- a React frontend for chat, agent management, knowledge base management, and document upload
- a PostgreSQL + pgvector storage layer for structured data and vector search
- hybrid retrieval using BM25 + vector similarity + reranking
- multi-format document parsing for `md`, `txt`, `pdf`, `docx`, `xlsx`, `xls`, and `csv`

The core user workflow is:

1. Create an agent and choose the model/tools/knowledge bases it can use.
2. Create a knowledge base and upload documents.
3. The backend parses documents, chunks them, embeds them, stores them, and builds BM25 indexes.
4. Start a chat session with an agent.
5. The agent runs a think-execute loop:
   decide -> call tools or retrieve knowledge -> observe results -> continue or finish.
6. The frontend receives incremental status and generated content over SSE.

## Core Capabilities

### 1. Agent Loop

The agent runtime lives in [JChatMind.java](/Users/xiaoxin/Downloads/JChatMind/jchatmind/src/main/java/com/kama/jchatmind/agent/JChatMind.java).

It maintains:

- conversation memory
- agent state
- tool execution flow
- step limit protection
- SSE message emission

Instead of letting Spring AI auto-run tools internally, the project disables internal tool execution and manages the loop explicitly. That makes the agent behavior easier to inspect and extend.

### 2. Tool Calling

Tools are organized through a shared interface under [jchatmind/src/main/java/com/kama/jchatmind/agent/tools](/Users/xiaoxin/Downloads/JChatMind/jchatmind/src/main/java/com/kama/jchatmind/agent/tools).

The current design supports:

- fixed tools that are always available to the agent runtime
- optional tools that can be enabled per agent
- manual tool execution result handling

This makes the system easier to reason about than a pile of prompt-driven `if/else` logic.

### 3. Knowledge Base and RAG

Document ingestion is handled mainly by:

- [DocumentFacadeServiceImpl.java](/Users/xiaoxin/Downloads/JChatMind/jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImpl.java)
- [DocumentParserServiceImpl.java](/Users/xiaoxin/Downloads/JChatMind/jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentParserServiceImpl.java)
- [ChunkingServiceImpl.java](/Users/xiaoxin/Downloads/JChatMind/jchatmind/src/main/java/com/kama/jchatmind/service/impl/ChunkingServiceImpl.java)
- [RagServiceImpl.java](/Users/xiaoxin/Downloads/JChatMind/jchatmind/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java)
- [HybridSearchServiceImpl.java](/Users/xiaoxin/Downloads/JChatMind/jchatmind/src/main/java/com/kama/jchatmind/service/impl/HybridSearchServiceImpl.java)

The retrieval pipeline works like this:

1. Upload a document.
2. Parse it into normalized `ParsedDocument` sections.
3. Chunk the content with structure-aware chunking.
4. Generate embeddings.
5. Store chunk text and vectors in PostgreSQL/pgvector.
6. Index chunk text into Lucene BM25.
7. At query time, run BM25 retrieval and vector retrieval in parallel.
8. Fuse the rankings with RRF.
9. Optionally rerank the fused candidates.
10. Return evidence to the agent or the UI.

## End-to-End Flow

### Ingestion Flow

```text
upload document
-> detect format
-> parse content
-> split into sections/chunks
-> embed text
-> write chunks to PostgreSQL
-> write BM25 index
-> mark document READY
```

### Query Flow

```text
user asks a question
-> create chat message
-> agent reads memory + system prompt
-> decide whether to answer / retrieve / call tools
-> if retrieval is needed:
   BM25 + vector search + RRF + rerank
-> agent continues reasoning
-> final answer is persisted
-> frontend receives updates by SSE
```

## Project Structure

```text
.
├── jchatmind/        # Spring Boot backend
├── ui/               # React + Vite frontend
├── jchatmind_assert/ # SQL and sample assertion assets
├── examples/         # small example files
└── README.md
```

Important backend areas:

- `controller/`: REST and SSE endpoints
- `service/`: business services for chat, documents, retrieval, and tools
- `agent/`: agent runtime and agent factory
- `mapper/` + `resources/mapper/`: MyBatis mappers and SQL
- `model/`: DTOs, VOs, entities, requests, and responses

Important frontend areas:

- `src/components/views/`: chat and knowledge base pages
- `src/api/`: API client layer
- `src/hooks/`: data fetching hooks

## Tech Stack

### Backend

- Java 17
- Spring Boot 3.5.8
- Spring AI 1.1.0
- MyBatis
- PostgreSQL
- pgvector
- Apache Tika
- Apache POI
- Lucene BM25

### Frontend

- React 19
- TypeScript
- Vite
- Ant Design 6
- Tailwind CSS

## Data Model

The main tables are defined in [jchatmind_assert/jchatmind.sql](/Users/xiaoxin/Downloads/JChatMind/jchatmind_assert/jchatmind.sql):

- `agent`
- `chat_session`
- `chat_message`
- `knowledge_base`
- `document`
- `chunk_bge_m3`

Together they model:

- agent configuration
- conversation state
- uploaded documents
- retrieval chunks
- vector embeddings

## Running the Project

### 1. Start infrastructure

You need:

- PostgreSQL with `pgvector`
- an embedding/reranking service reachable by the backend
- API keys for the configured chat models

### 2. Configure the backend

The current [application.yaml](/Users/xiaoxin/Downloads/JChatMind/jchatmind/src/main/resources/application.yaml) contains local development values. Before using this project outside a local experiment, move secrets such as database passwords, mail credentials, and model API keys to environment variables or a private config file.

### 3. Start the backend

```bash
cd jchatmind
./mvnw spring-boot:run
```

### 4. Start the frontend

```bash
cd ui
npm install
npm run dev
```

### 5. Open the app

By default:

- backend: `http://localhost:8080`
- frontend: Vite default dev server

## Current Engineering Focus

From the current codebase, the main engineering themes are:

- improving multi-format parsing
- making chunking more content-aware instead of hardcoded per document type
- strengthening hybrid retrieval and reranking
- making knowledge-base-backed answers more evidence-driven
- improving observability through tests and manual inspection examples

## Why This Project Is Interesting

This project is a good reference if you want to study how to build an AI application that is more than prompt templating. It combines:

- backend system design
- agent orchestration
- knowledge ingestion
- search and ranking
- real-time frontend feedback

In short: JChatMind is an implementation of an inspectable, extensible agent system with a real document ingestion and retrieval pipeline behind it.
