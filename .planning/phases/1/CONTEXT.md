# Phase 1 Context: Multi-Format Document Parser

## Decisions

### 1. Parser Library: Apache Tika

**Decision:** Use `tika-core` + `tika-parsers-standard-package` as the unified document parsing library.

**Why:** Standard Java ecosystem solution, single API for all formats, avoids maintaining multiple parser implementations (poi + pdfbox + etc.).

**How to apply:** Add to `pom.xml`, use `Tika` class or `AutoDetectParser` for format-agnostic parsing.

### 2. Output Type: Replace MarkdownSection with ParsedDocument

**Decision:** Create a new `ParsedDocument` type that replaces `MarkdownSection`. Update `MarkdownParserService` interface to return `List<ParsedDocument>` instead of `List<MarkdownSection>`.

**Structure:**
```java
class ParsedDocument {
    private String title;          // section/chapter title
    private String content;        // plain text content under this title
    private List<String> hierarchy; // title path, e.g. ["Chapter 1", "Section 2"]
    private String sourceFormat;   // "md", "pdf", "docx", "txt", "xlsx", "csv"
}
```

**Why:** All parsers (Markdown, TXT, DOCX, PDF, Excel) should produce the same output type. The `hierarchy` field enables Phase 2 chunk metadata with title paths.

**How to apply:** Rename or replace `MarkdownParserService` → `DocumentParserService` interface with `parse(InputStream, String format)` returning `List<ParsedDocument>`. Existing `MarkdownParserServiceImpl` adapts to return this type.

### 3. Format Detection: File Extension (Keep Current)

**Decision:** Keep the existing `getFileType(filename)` method that extracts extension from filename.

**Why:** Simple, fast, no additional dependency overhead. Content-based detection adds value only for edge cases (wrong extensions) which are rare in this use case.

**How to apply:** In `DocumentFacadeServiceImpl`, route by the existing `filetype` string. Add a `DocumentParserService` that accepts a format string and dispatches to the right parser implementation.

### 4. Error Handling: Surface to User

**Decision:** When parsing fails (corrupted file, password-protected PDF, etc.), throw a `BizException` with a clear message instead of silently swallowing the error.

**Why:** User needs to know why their document wasn't processed. Current Markdown handler silently catches exceptions at line 262 of `DocumentFacadeServiceImpl`, which hides failures.

**How to apply:** In the new parser dispatch logic, catch parsing exceptions and re-throw as `BizException` with user-friendly messages like "PDF 文件已加密，无法解析" or "DOCX 文件已损坏"。

### 5. Excel/CSV Format: Markdown Table Output

**Decision:** Convert Excel/CSV table rows to Markdown table format (`| col1 | col2 | ...`).

**Why:** Consistent with existing `MarkdownSection` output, compatible with Phase 2 chunking (Markdown tables chunk well by row groups).

**How to apply:** For `.xlsx`/`.csv` files, read rows via Tika's table extraction or apache-poi, format each sheet as a Markdown table with header row.

## Implementation Plan

### New files to create:
- `DocumentParserService.java` — interface replacing `MarkdownParserService`
- `ParsedDocument.java` — unified output model
- `TxtParserServiceImpl.java` — TXT parser
- `DocxParserServiceImpl.java` — DOCX parser
- `PdfParserServiceImpl.java` — PDF parser
- `ExcelParserServiceImpl.java` — Excel/CSV parser

### Files to modify:
- `pom.xml` — add `tika-core` + `tika-parsers-standard-package` dependencies
- `MarkdownParserService.java` — change return type to `List<ParsedDocument>`, rename interface
- `MarkdownParserServiceImpl.java` — adapt to return `ParsedDocument`
- `DocumentFacadeServiceImpl.java` — route by filetype to correct parser, surface errors

## Scope Boundaries

**In scope:**
- TXT, DOCX, PDF, XLSX, CSV parsing
- Unified `ParsedDocument` output
- Format routing in upload flow
- Error surfacing

**Out of scope (deferred):**
- Password-protected file decryption — reject with clear message
- Image/OCR extraction from PDF — text-only for now
- Chunk size configuration — Phase 2
- Semantic boundary chunking — Phase 2
- BM25 indexing — Phase 3
