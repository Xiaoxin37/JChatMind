# Phase 1 Plan: Multi-Format Document Parser

**Goal:** 系统能解析 TXT、DOCX、PDF、Excel/CSV 文件，替代仅支持 Markdown 的现状

**Phase:** 1 — multi-format-document-parser

## Implementation Tasks

### Task 1: Add Apache Tika Dependencies to pom.xml

**Dependencies to add** (in `<dependencies>` block, after flexmark):

```xml
<!-- Apache Tika for multi-format document parsing -->
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>3.3.0</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>3.3.0</version>
</dependency>
<!-- Apache POI for direct Excel handling (transitively available via Tika but explicit version control) -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi</artifactId>
    <version>5.4.1</version>
</dependency>
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.4.1</version>
</dependency>
<!-- OpenCSV for CSV parsing with quoted field support -->
<dependency>
    <groupId>com.opencsv</groupId>
    <artifactId>opencsv</artifactId>
    <version>5.10</version>
</dependency>
```

**Why explicit POI and OpenCSV:** Tika flattens Excel table structure; we need direct POI access for row-by-row Markdown table conversion. OpenCSV handles quoted fields and escaped commas correctly.

**Verify:** After adding, run `mvn dependency:tree -pl jchatmind` to check for version conflicts with Spring AI BOM.

### Task 2: Create `ParsedDocument.java` Model

**File:** `jchatmind/src/main/java/com/kama/jchatmind/model/dto/ParsedDocument.java`

```java
package com.kama.jchatmind.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * Unified parsed document structure from any format parser.
 * Replaces MarkdownSection for all downstream processing.
 */
@Data
@AllArgsConstructor
@ToString
public class ParsedDocument {
    /** Section/chapter title */
    private String title;
    /** Plain text content under this title */
    private String content;
    /** Title path, e.g. ["Chapter 1", "Section 2"] */
    private List<String> hierarchy;
    /** Source format: "md", "pdf", "docx", "txt", "xlsx", "csv" */
    private String sourceFormat;
}
```

### Task 3: Create `DocumentParserService.java` Interface

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/DocumentParserService.java`

```java
package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ParsedDocument;

import java.io.InputStream;
import java.util.List;

/**
 * Unified document parser interface.
 * Each format (TXT, DOCX, PDF, Excel/CSV, Markdown) has its own implementation.
 */
public interface DocumentParserService {
    /**
     * Parse document content into structured sections.
     *
     * @param inputStream Document input stream
     * @param format      File format extension (e.g. "txt", "docx", "pdf", "xlsx", "csv", "md")
     * @return List of parsed document sections
     */
    List<ParsedDocument> parse(InputStream inputStream, String format);
}
```

### Task 4: Create Format-Specific Parser Implementations

#### 4a. `TxtParserServiceImpl.java`

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/impl/TxtParserServiceImpl.java`

- Read all bytes as UTF-8 text (with fallback to GBK if UTF-8 fails, common for Chinese text files)
- If content contains Markdown-like headings (regex: `^#{1,6} .*$` with multiline flag), parse using heading-based logic — treat `# ` headings as section titles, content between headings as section content
- If no Markdown-like headings: treat entire file as single `ParsedDocument` with title "文档内容"
- `sourceFormat = "txt"`

#### 4b. `DocxParserServiceImpl.java`

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocxParserServiceImpl.java`

- Use Apache Tika's `AutoDetectParser` with `BodyContentHandler` (limit: 10MB)
- Parse to XHTML output to extract `<h1>`-`<h6>` heading tags
- Track heading hierarchy: each heading starts a new `ParsedDocument` section
- Content between headings accumulated as section content
- Use `OfficeParserConfig.setIncludeHeadersAndFooters(false)` to prevent header/footer leakage
- `sourceFormat = "docx"`

#### 4c. `PdfParserServiceImpl.java`

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/impl/PdfParserServiceImpl.java`

- Use Apache Tika's `AutoDetectParser` with `BodyContentHandler` (limit: 10MB)
- Extract text content from PDF (Tika uses PDFBox internally)
- For heading structure: use filename (without extension) as the root title
- If content length > 2000 chars, split by double-newline (`\n\n`) into sections titled "段落 1", "段落 2", etc.
- For shorter documents, return as single `ParsedDocument`
- `sourceFormat = "pdf"`

**Note on ROADMAP.md success criterion #3 (提取段落和标题结构):** PDF heading extraction is fundamentally limited since PDF is a presentation format. Phase 1 extracts paragraphs (via double-newline splitting) but uses filename as title instead of recovering heading hierarchy. Full heading recovery (font-size heuristics or PDF bookmarks) is deferred to Phase 2. This is an acknowledged scope reduction documented here explicitly.

#### 4d. `ExcelParserServiceImpl.java`

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/impl/ExcelParserServiceImpl.java`

- Use Apache POI directly (not Tika) for structured row/cell access
- For `.xlsx`: `XSSFWorkbook`, for `.csv`: use `com.opencsv.CSVReader` with auto-detected encoding (Tika `CharsetDetector`)
- Each sheet becomes one `ParsedDocument` section with sheet name as title
- Convert rows to Markdown table format: `| col1 | col2 | col3 |`
- First row treated as header row with separator: `|---|---|---|`
- `sourceFormat = "xlsx"` or `"csv"`

### Task 5: Refactor `MarkdownParserServiceImpl.java`

**Changes:**
- Change return type of `parseMarkdown` from `List<MarkdownSection>` to `List<ParsedDocument>`
- Build `ParsedDocument` objects with `sourceFormat = "md"`, `hierarchy` derived from heading nesting
- Keep existing parsing logic intact — just adapt the output type

### Task 6: Refactor `MarkdownParserService.java` Interface

**Changes:**
- Rename return type: `List<ParsedDocument> parseMarkdown(InputStream inputStream)`
- Remove inner `MarkdownSection` class (replaced by `ParsedDocument`)
- Keep interface name as-is for now (avoid cascading rename across all callers)

### Task 7: Update `DocumentFacadeServiceImpl.java`

**Changes in `uploadDocument` method:**
- Replace the `if ("md" || "markdown")` check with a switch/dispatch on `filetype`
- Supported types: `md`, `markdown`, `txt`, `docx`, `pdf`, `xlsx`, `csv`
- For supported types, call `documentParserService.parse(inputStream, filetype)`
- For unsupported types, log warning as before

**Changes in `processMarkdownDocument` method (rename to `processDocument`):**
- Accept `filetype` parameter
- Use `documentParserService.parse()` instead of `markdownParserService.parseMarkdown()`
- Iterate over `List<ParsedDocument>` instead of `List<MarkdownSection>`
- Build chunk content using `section.getContent()`, title from `section.getTitle()`
- Store hierarchy path in chunk metadata (serialize `section.getHierarchy()` as JSON string)

**Error handling:**
- Catch parsing exceptions and re-throw as `BizException` with user-friendly messages:
  - TXT: "TXT 文件编码无法识别，请尝试保存为 UTF-8 格式"
  - DOCX: "DOCX 文件已损坏或格式不支持"
  - PDF: "PDF 文件已加密或已损坏，无法解析"
  - Excel/CSV: "Excel/CSV 文件格式错误或包含不可读字符"

### Task 8: Inject `DocumentParserService` in `DocumentFacadeServiceImpl`

- Add `private final DocumentParserService documentParserService;` field (constructor injection via `@AllArgsConstructor`)
- This replaces the direct dependency on `MarkdownParserService` for the upload flow

### Task 9: Create Parser Dispatcher (MUST EXECUTE BEFORE Task 7)

**File:** `jchatmind/src/main/java/com/kama/jchatmind/service/impl/DocumentParserServiceImpl.java`

- Implements `DocumentParserService` interface
- Contains a map of format → parser implementation (injected via Spring)
- `parse()` method dispatches to the correct parser based on `format` parameter
- Unknown formats throw `BizException("不支持的文件格式: " + format)`

**Parser implementations as Spring beans:**
- `TxtParserServiceImpl` → `@Service`, implements a method `List<ParsedDocument> parseTxt(InputStream)`
- `DocxParserServiceImpl` → `@Service`, implements a method `List<ParsedDocument> parseDocx(InputStream)`
- `PdfParserServiceImpl` → `@Service`, implements a method `List<ParsedDocument> parsePdf(InputStream)`
- `ExcelParserServiceImpl` → `@Service`, implements a method `List<ParsedDocument> parseExcel(InputStream, String format)`

### Task 10: Update Frontend File Upload Component

**File:** `ui/src/components/views/KnowledgeBaseView.tsx`

- Update the `accept` attribute on the file input from `.md` to `.md,.txt,.docx,.pdf,.xlsx,.csv`
- This allows users to select the new file types in the browser file picker
- No other frontend changes needed — the backend handles format detection and parsing

## Task Dependencies

```
Task 1 (pom.xml deps) ──────────────────┐
                                         │  (parallel)
Task 2 (ParsedDocument) ──────────────┐ │
Task 3 (DocumentParserService) ───────┤ │
                                       ├── Task 4a-4d (Parser Implementations)
                                       │
Task 2 + Task 3 ───────────────────────┤
                                       ├── Task 5-6 (Refactor Markdown)
                                       │
Task 4a-4d ────────────────────────────┤
                                       ├── Task 9 (Parser Dispatcher)
Task 9 ────────────────────────────────┤
                                       ├── Task 7 (Facade Update)
Task 5-6 ──────────────────────────────┘

Task 10 (Frontend) ──────────────────── independent, can run anytime
```

**Execution order:**
1. Task 1 (pom.xml deps)
2. Task 2-3 (model + interface) — parallel after Task 1
3. Task 4a-4d (parser implementations) — parallel after 2-3
4. Task 5-6 (refactor Markdown) — parallel after 2-3
5. Task 9 (Parser Dispatcher) — after 4a-4d
6. Task 7 (Facade Update) — after Task 9 and 5-6
7. Task 8 (Injection) — part of Task 7
8. Task 10 (Frontend) — independent, can run anytime

## Verification Criteria

After implementation:

1. Upload `.txt` file → `ParsedDocument` with correct text content
2. Upload `.docx` file → `ParsedDocument` list with heading-based sections
3. Upload `.pdf` file → `ParsedDocument` with extracted text
4. Upload `.xlsx`/`.csv` file → `ParsedDocument` with Markdown table content
5. Upload `.md` file → same behavior as before (no regression)
6. Unsupported format → clear error message, not silent failure
7. Existing `DocumentController` upload endpoint works without changes

## Scope Boundaries (from CONTEXT.md)

**In scope:**
- TXT, DOCX, PDF, XLSX, CSV parsing
- Unified `ParsedDocument` output
- Format routing in upload flow
- Error surfacing

**Out of scope:**
- Password-protected file decryption
- Image/OCR extraction from PDF
- Chunk size configuration (Phase 2)
- Semantic boundary chunking (Phase 2)
- BM25 indexing (Phase 3)
