# Phase 1: Multi-Format Document Parser - Research

**Researched:** 2026-04-23
**Domain:** Apache Tika document parsing, Spring Boot integration, Strategy Pattern
**Confidence:** HIGH

## Summary

This research covers the integration of Apache Tika 3.3.0 (latest, confirmed via Maven Central) into the existing Spring Boot 3.5.8 + Java 17 project for parsing TXT, DOCX, PDF, Excel, and CSV files. Apache Tika was already decided as the parsing library by the user (CONTEXT.md Decision #1). The research focuses on proper Maven dependency setup, strategy pattern implementation for format-specific parsers, format-specific extraction approaches, and known pitfalls with Tika.

Key finding: Apache Tika outputs XHTML with heading tags (`<h1>`, `<h2>`, etc.) for DOCX files, preserving title hierarchy. For PDFs, Tika produces flat text only — heading structure must be reconstructed via post-processing (font-size heuristics or PDF bookmark extraction). For Excel/CSV, Tika extracts text but does NOT preserve row/column structure; Apache POI should be used directly for Excel and standard CSV parsing for CSV to produce Markdown table output.

**Primary recommendation:** Use `AutoDetectParser` with `SAX` event handler to capture XHTML output for DOCX (preserves headings), fall back to direct Apache POI for Excel/CSV (need row/column data for Markdown tables), and use simple text reading for TXT.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

1. **Parser Library: Apache Tika** — Use `tika-core` + `tika-parsers-standard-package` as the unified document parsing library. Standard Java ecosystem solution, single API for all formats.

2. **Output Type: Replace MarkdownSection with ParsedDocument** — Create `ParsedDocument` with fields: `title`, `content`, `hierarchy` (List<String>), `sourceFormat`. Update `MarkdownParserService` interface to return `List<ParsedDocument>`.

3. **Format Detection: File Extension (Keep Current)** — Keep existing `getFileType(filename)` method. Content-based detection adds value only for edge cases.

4. **Error Handling: Surface to User** — Throw `BizException` with clear messages instead of silently swallowing errors.

5. **Excel/CSV Format: Markdown Table Output** — Convert Excel/CSV table rows to Markdown table format (`| col1 | col2 | ...`).

### Claude's Discretion

None explicitly stated — all major decisions are locked by the user.

### Deferred Ideas (OUT OF SCOPE)

- Password-protected file decryption — reject with clear message
- Image/OCR extraction from PDF — text-only for now
- Chunk size configuration — Phase 2
- Semantic boundary chunking — Phase 2
- BM25 indexing — Phase 3
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PARSE-01 | 系统能解析 `.txt` 纯文本文件并提取内容 | Read file as UTF-8 string, split by blank lines or newlines into sections |
| PARSE-02 | 系统能解析 `.docx` Word 文档并提取文本（含标题层级） | Tika AutoDetectParser outputs XHTML with `<h1>`-`<h6>` tags; parse SAX events to extract heading hierarchy |
| PARSE-03 | 系统能解析 `.pdf` 文档并提取文本（含段落、标题结构） | Tika produces flat text; heading structure must be approximated via post-processing or PDF bookmarks |
| PARSE-04 | 系统能解析 `.xlsx`/`.csv` 文件并将表格转为结构化文本 | Use Apache POI (xlsx) + CSVReader (csv) for row/column data; convert to Markdown tables |
| PARSE-05 | 系统能解析 `.md` 文件（保持现有能力） | Existing Flexmark-based MarkdownParserServiceImpl, adapted to return ParsedDocument |
| PARSE-06 | 上传文件后自动识别文件格式并路由到对应解析器 | File extension routing in DocumentFacadeServiceImpl, dispatch to format-specific parser |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| File upload handling | API / Backend | — | Spring MVC multipart handling in DocumentFacadeServiceImpl |
| File type detection | API / Backend | — | Extension-based detection via `getFileType()` |
| Document parsing (all formats) | API / Backend | — | In-process Tika parsing in parser service implementations |
| DOCX heading extraction | API / Backend | — | Tika SAX XHTML output parsing |
| PDF text extraction | API / Backend | — | Tika AutoDetectParser, flat text output |
| Excel/CSV to Markdown table | API / Backend | — | Apache POI direct access (xlsx) + CSV reading |
| Markdown parsing | API / Backend | — | Existing Flexmark-based implementation |
| Error surface to user | API / Backend | — | BizException in facade layer |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.apache.tika:tika-core` | 3.3.0 | Core Tika API (Parser interface, AutoDetectParser, MIME detection) | Verified latest via Maven Central (2026-03-23). Required base dependency. |
| `org.apache.tika:tika-parsers-standard-package` | 3.3.0 | All standard format parsers (PDF, DOCX, XLSX, CSV, TXT) | Brings in PDFBox, POI, and other parsers transitively. Must match tika-core version. |
| `org.apache.poi:poi-ooxml` | 5.4.1 (managed by Tika) | Direct Excel (.xlsx) access for row/column extraction | Tika's ExcelExtractor loses row/column structure. POI gives cell-level access needed for Markdown table output. |
| `com.opencsv:opencsv` | 5.10 (managed by Tika) | CSV parsing with row/column access | Needed because Tika CSV output is flat text, not structured rows. |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.springframework.boot:spring-boot-starter-web` | 3.5.8 (existing) | MultipartFile handling | Already in project |
| `com.vladsch.flexmark:flexmark-all` | 0.64.8 (existing) | Markdown parsing | Already in project for PARSE-05 |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Tika AutoDetectParser | Tika server (REST API) | Server adds deployment complexity; embedded is simpler for this project size |
| Apache POI for Excel | Tika ExcelExtractor alone | Tika loses row/column data; POI is necessary for Markdown table output |
| OpenCSV for CSV | Tika CSVParser alone | Same issue — Tika produces flat text, OpenCSV gives structured rows |

**Installation (pom.xml additions):**
```xml
<!-- Apache Tika: unified document parsing -->
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
```

Note: `tika-parsers-standard-package` transitively includes Apache POI and OpenCSV, so no separate version declaration is needed. The planner should verify resolved versions with `mvn dependency:tree`.

**Version verification:** Confirmed via Maven Central API:
- `tika-core` latest: 3.3.0 (verified 2026-04-23)
- `tika-parsers-standard-package` latest: 3.3.0 (verified 2026-04-23)

## Architecture Patterns

### System Architecture Diagram

```
MultipartFile Upload
        │
        ▼
┌─────────────────────────────────────┐
│   DocumentFacadeServiceImpl         │
│   getFileType(filename) → ext       │
│   ┌───────────────────────────────┐ │
│   │  Format Router (switch ext)   │ │
│   │   md → MarkdownParserService  │ │
│   │   txt → TxtParserService      │ │
│   │   docx → DocxParserService    │ │
│   │   pdf → PdfParserService      │ │
│   │   xlsx/csv → ExcelParserService│ │
│   └───────────────────────────────┘ │
└─────────────────────────────────────┘
        │
        ▼ (all return List<ParsedDocument>)
┌─────────────────────────────────────┐
│   ParsedDocument                    │
│   title / content / hierarchy / fmt │
└─────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────┐
│   Chunk Generation (Phase 2)        │
│   Embedding → ChunkBgeM3 insert     │
└─────────────────────────────────────┘
```

### Recommended Project Structure
```
jchatmind/src/main/java/com/kama/jchatmind/
├── model/entity/
│   └── ParsedDocument.java          # unified output model
├── service/
│   ├── DocumentParserService.java   # interface (replaces MarkdownParserService)
│   ├── impl/
│   │   ├── MarkdownParserServiceImpl.java  # adapted to return ParsedDocument
│   │   ├── TxtParserServiceImpl.java       # TXT parser
│   │   ├── DocxParserServiceImpl.java      # DOCX parser (Tika SAX XHTML)
│   │   ├── PdfParserServiceImpl.java       # PDF parser (Tika flat text)
│   │   └── ExcelParserServiceImpl.java     # Excel/CSV parser (POI + OpenCSV)
│   └── DocumentFacadeServiceImpl.java      # format router, error handling
```

### Pattern 1: Strategy Pattern for Format Parsers
**What:** Each file format gets its own `DocumentParserService` implementation. A central dispatch method routes by file extension.

**When to use:** When you have multiple parsing algorithms that produce the same output type (`List<ParsedDocument>`).

**Example:**
```java
// Interface
public interface DocumentParserService {
    List<ParsedDocument> parse(InputStream inputStream, String format);
    boolean supports(String format);
}

// Implementation example (DOCX)
@Service
@Slf4j
public class DocxParserServiceImpl implements DocumentParserService {
    private final AutoDetectParser tikaParser;

    public DocxParserServiceImpl() {
        this.tikaParser = new AutoDetectParser();
    }

    @Override
    public List<ParsedDocument> parse(InputStream inputStream, String format) {
        try {
            // Use Tika SAX handler to capture XHTML heading tags
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            tikaParser.parse(inputStream, handler, metadata, context);

            String xhtml = handler.toString();
            // Parse XHTML to extract <h1>-<h6> tags and build hierarchy
            return parseXhtmlHeadings(xhtml);
        } catch (Exception e) {
            throw new BizException("DOCX 文件解析失败: " + e.getMessage());
        }
    }

    @Override
    public boolean supports(String format) {
        return "docx".equalsIgnoreCase(format);
    }
}
```

### Pattern 2: Format Router in Facade
**What:** `DocumentFacadeServiceImpl` uses a Spring-injected list of all `DocumentParserService` implementations and routes by `supports()` check.

**When to use:** When the routing decision should be centralized but each parser knows its own supported formats.

**Example:**
```java
@Service
@AllArgsConstructor
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {
    private final List<DocumentParserService> parsers;  // Spring auto-wires all impls

    private List<ParsedDocument> parseDocument(InputStream is, String filetype) {
        return parsers.stream()
                .filter(p -> p.supports(filetype))
                .findFirst()
                .orElseThrow(() -> new BizException("不支持的文件格式: " + filetype))
                .parse(is, filetype);
    }
}
```

### Anti-Patterns to Avoid
- **Single monolithic parser with giant switch-case:** Each format has unique extraction logic. Splitting into services keeps code maintainable.
- **Using Tika for Excel/CSV structured data:** Tika produces flat text from spreadsheets. Use Apache POI and OpenCSV directly for row/column access.
- **Silently swallowing parse exceptions:** Decision #4 requires surfacing errors as `BizException`. Do not catch and log-only like the current Markdown handler does (line 262-265).
- **Reading entire large files into memory:** Use Tika's `BodyContentHandler` with size limits and streaming parsers for large files.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| MIME type detection | Custom content-type detection from file bytes | Tika `Tika.detect()` or file extension | Tika has comprehensive mime-types.xml database |
| PDF text extraction | PDFBox custom extraction logic | Tika `AutoDetectParser` with PDFBox underneath | Tika already wraps PDFBox; no benefit to direct usage |
| DOCX text extraction | Apache POI XWPFDocument traversal | Tika `AutoDetectParser` + SAX XHTML handler | Tika handles OOXML complexity, SAX is memory-efficient |
| TXT encoding detection | Manual charset guessing | Tika `CharsetDetector` or assume UTF-8 | Most project docs will be UTF-8; Tika can detect if needed |
| Excel/CSV table parsing | Custom CSV splitter by comma | OpenCSV for CSV, Apache POI for XLSX | Handles quoted fields, escaped characters, merged cells |

**Key insight:** Tika is excellent as a unified text extraction layer for TXT, DOCX, and PDF. But for Excel/CSV where structured row/column data is required (for Markdown table output), direct use of Apache POI + OpenCSV is necessary because Tika's output flattens table structure.

## Runtime State Inventory

> Not applicable — this is a greenfield phase adding new parsing capabilities, not a rename/refactor/migration phase.

## Common Pitfalls

### Pitfall 1: Tika OOM on Large Files
**What goes wrong:** Tika loads entire documents into memory. Large PDFs or DOCX files cause `OutOfMemoryError`.
**Why it happens:** `BodyContentHandler` has a default 100KB limit. Setting `-1` removes the limit entirely.
**How to avoid:** Set a reasonable size limit on `BodyContentHandler` (e.g., `new BodyContentHandler(10 * 1024 * 1024)` for 10MB). For very large files, consider `ParsingEmbeddedDocumentExtractor` or chunked reading.
**Warning signs:** `java.lang.OutOfMemoryError: Java heap space` during parsing; `BodyContentHandler` throws `SAXException` when limit exceeded.

### Pitfall 2: Tika PDF Heading Loss
**What goes wrong:** Tika extracts flat text from PDFs with no heading level information. All text appears as paragraphs.
**Why it happens:** PDF is a presentation format, not a structural format. PDFBox (which Tika uses internally) extracts text by visual position, not by semantic structure.
**How to avoid:** Use PDF bookmarks/outlines as a fallback for heading structure (via `PDDocument.getDocumentCatalog().getOutline()`). Alternatively, post-process with font-size heuristics: text that is larger/bolder than surrounding text is likely a heading. For Phase 1, recommend flat text extraction with single "Document" as root title — heading recovery can improve in Phase 2.
**Warning signs:** All `ParsedDocument` entries from PDF have the same title level, no hierarchy.

### Pitfall 3: Tika Dependency Bloat and Conflicts
**What goes wrong:** `tika-parsers-standard-package` pulls in 100+ transitive dependencies including multiple XML parsers, image libraries, and POI versions.
**Why it happens:** Tika supports hundreds of formats; the standard package includes parsers for all common ones.
**How to avoid:** Only use `tika-parsers-standard-package` (NOT the full `tika-parsers`). Review `mvn dependency:tree` for conflicts with existing dependencies. Exclude unnecessary transitive deps if they conflict with Spring AI BOM.
**Warning signs:** `NoSuchMethodError`, `ClassNotFoundException`, or version conflicts in `mvn dependency:tree`.

### Pitfall 4: DOCX Header/Footer Content Inclusion
**What goes wrong:** Tika by default extracts text from headers, footers, and footnotes, mixing them into body content.
**Why it happens:** `BodyContentHandler` processes all text nodes in the XHTML output.
**How to avoid:** Use `OfficeParserConfig` to exclude headers/footers:
```java
OfficeParserConfig config = new OfficeParserConfig();
config.setIncludeHeadersAndFooters(false);
context.set(OfficeParserConfig.class, config);
```
**Warning signs:** Page numbers, document titles, and footer text appear interspersed in extracted content.

### Pitfall 5: CSV Encoding Issues
**What goes wrong:** CSV files with non-UTF-8 encoding (GBK, GB2312 for Chinese docs) produce garbled text.
**Why it happens:** OpenCSV and standard Java readers default to platform encoding.
**How to avoid:** Use `CharsetDetector` from Tika to detect encoding before reading CSV. For known Chinese documents, try GBK fallback:
```java
Charset charset = CharsetDetector.detect(inputStream).getCharset();
try (InputStreamReader reader = new InputStreamReader(inputStream, charset)) {
    CSVReader csvReader = new CSVReader(reader);
    // ...
}
```
**Warning signs:** Extracted CSV content contains `???` or garbled Chinese characters.

### Pitfall 6: Excel Merged Cells and Multi-Sheet Documents
**What goes wrong:** Excel files with merged cells, multiple sheets, or complex formulas produce incorrect Markdown tables.
**Why it happens:** POI's cell iteration doesn't naturally handle merged cells; each sheet must be processed separately.
**How to avoid:** For Phase 1, handle single-sheet simple tables. For multi-sheet files, concatenate sheets with a separator. Log a warning for merged cells.
**Warning signs:** Misaligned Markdown table columns, missing cells in output.

## Code Examples

### DOCX Heading Extraction from Tika XHTML Output
```java
// Source: Apache Tika documentation + custom XHTML parsing
private List<ParsedDocument> parseXhtmlHeadings(String xhtml) {
    List<ParsedDocument> docs = new ArrayList<>();
    // Tika outputs XHTML like:
    // <h1>Chapter 1</h1><p>Content here...</p>
    // <h2>Section 1.1</h2><p>More content...</p>

    // Use a simple regex or SAX parser to extract headings and content
    Pattern headingPattern = Pattern.compile("<h(\\d)>(.*?)</h\\d>", Pattern.DOTALL);
    Matcher m = headingPattern.matcher(xhtml);

    List<String> currentHierarchy = new ArrayList<>();
    String lastContent = null;
    String lastTitle = null;

    while (m.find()) {
        int level = Integer.parseInt(m.group(1));
        String title = Jsoup.parse(m.group(2)).text(); // strip HTML tags

        // Adjust hierarchy based on level
        while (currentHierarchy.size() >= level) {
            currentHierarchy.remove(currentHierarchy.size() - 1);
        }
        currentHierarchy.add(title);

        // Save previous section if exists
        if (lastTitle != null && lastContent != null) {
            docs.add(new ParsedDocument(lastTitle, lastContent,
                    new ArrayList<>(currentHierarchy.subList(0, currentHierarchy.size() - 1)),
                    "docx"));
        }
        lastTitle = title;
        lastContent = ""; // Will collect content between headings
    }
    // ... collect content between headings and add last section
    return docs;
}
```

### Excel to Markdown Table Conversion
```java
// Source: Apache POI documentation
public List<ParsedDocument> parseExcel(InputStream inputStream) {
    List<ParsedDocument> docs = new ArrayList<>();
    try (Workbook wb = WorkbookFactory.create(inputStream)) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet sheet = wb.getSheetAt(i);
            StringBuilder mdTable = new StringBuilder();
            String sheetName = sheet.getSheetName();

            Iterator<Row> rowIterator = sheet.iterator();
            if (rowIterator.hasNext()) {
                // Header row
                Row headerRow = rowIterator.next();
                mdTable.append(formatMarkdownRow(headerRow));
                mdTable.append("\n");
                // Separator row
                for (int j = 0; j < headerRow.getLastCellNum(); j++) {
                    mdTable.append("| --- ");
                }
                mdTable.append("|\n");
                // Data rows
                while (rowIterator.hasNext()) {
                    mdTable.append(formatMarkdownRow(rowIterator.next()));
                    mdTable.append("\n");
                }
            }

            docs.add(new ParsedDocument(sheetName, mdTable.toString(),
                    List.of(sheetName), "xlsx"));
        }
    } catch (Exception e) {
        throw new BizException("Excel 文件解析失败: " + e.getMessage());
    }
    return docs;
}

private String formatMarkdownRow(Row row) {
    StringBuilder sb = new StringBuilder();
    int lastCell = row.getLastCellNum();
    for (int j = 0; j < lastCell; j++) {
        Cell cell = row.getCell(j, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        sb.append("| ");
        sb.append(cell != null ? getCellValueAsString(cell).replace("|", "\\|") : "");
    }
    sb.append("|");
    return sb.toString();
}
```

### CSV to Markdown Table Conversion
```java
// Source: OpenCSV documentation
public List<ParsedDocument> parseCsv(InputStream inputStream) {
    List<ParsedDocument> docs = new ArrayList<>();
    try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
         CSVReader csvReader = new CSVReader(reader)) {

        List<String[]> rows = csvReader.readAll();
        if (rows.isEmpty()) return docs;

        StringBuilder mdTable = new StringBuilder();
        // Header
        mdTable.append(formatMarkdownRow(rows.get(0)));
        mdTable.append("\n");
        // Separator
        for (int i = 0; i < rows.get(0).length; i++) {
            mdTable.append("| --- ");
        }
        mdTable.append("|\n");
        // Data
        for (int i = 1; i < rows.size(); i++) {
            mdTable.append(formatMarkdownRow(rows.get(i)));
            mdTable.append("\n");
        }

        docs.add(new ParsedDocument("Table", mdTable.toString(),
                List.of("Table"), "csv"));
    } catch (Exception e) {
        throw new BizException("CSV 文件解析失败: " + e.getMessage());
    }
    return docs;
}
```

### TXT Parser
```java
// Simple TXT parsing — split by double newlines into sections
public List<ParsedDocument> parseTxt(InputStream inputStream) {
    List<ParsedDocument> docs = new ArrayList<>();
    try {
        String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        // If content has Markdown-like headings (## heading), parse those
        // Otherwise, treat entire file as one section
        if (content.matches("(?s).*^#{1,6} .*$")) {
            // Has Markdown-style headings — use Markdown parser logic
            return parseMarkdownLikeTxt(content);
        }
        // Simple: single section
        docs.add(new ParsedDocument("Document", content, List.of("Document"), "txt"));
    } catch (Exception e) {
        throw new BizException("TXT 文件解析失败: " + e.getMessage());
    }
    return docs;
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Multiple parser libs (PDFBox + POI + custom) | Single Tika API for all formats | Tika 2.x+ | Reduced dependency management, unified interface |
| `tika-parsers` (all parsers including OCR, CAD, etc.) | `tika-parsers-standard-package` | Tika 2.x | Smaller dependency tree, fewer conflicts |
| `Tika` convenience class | `AutoDetectParser` + explicit handlers | Tika 2.x+ | Better control over parsing behavior and limits |
| Tika 1.x (Java 8) | Tika 3.x (Java 11+) | Tika 3.0 (Oct 2024) | Modern Java features, security fixes |

**Deprecated/outdated:**
- **Tika 1.x**: End of life. Use 3.x for Java 17 projects. 1.x had security vulnerabilities.
- **`tika-app` JAR as library**: Only use `tika-core` + `tika-parsers-standard-package`. `tika-app` is for CLI usage.
- **`new Tika().parseToString()`**: Too simplistic for structured output. Use `AutoDetectParser` with custom `ContentHandler` for heading-aware extraction.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Tika 3.3.0 is the latest version as of April 2026 | Standard Stack | Low — verified via Maven Central API |
| A2 | Tika XHTML output for DOCX contains `<h1>`-`<h6>` tags mapped from Word heading styles | Code Examples / DOCX approach | Medium — if Tika's XHTML doesn't preserve heading levels, the DOCX parser needs to use Apache POI XWPF directly instead |
| A3 | Apache POI and OpenCSV are transitively included by `tika-parsers-standard-package` | Standard Stack | Low — can always declare explicitly if versions differ |
| A4 | PDF bookmark extraction via PDFBookmarksHandler is available in Tika 3.x | PDF Pitfall | Medium — TIKA-2303 was optional; may need to configure ParseContext explicitly |

## Open Questions

1. **PDF heading extraction quality**
   - What we know: Tika produces flat text from PDFs. PDF bookmarks can be extracted but many PDFs don't have bookmarks.
   - What's unclear: How effective is font-size-based heading detection for typical project documents?
   - Recommendation: For Phase 1, use single "Document" as root title for PDF content. Defer heading recovery to Phase 2 or future improvement.

2. **Tika XHTML heading tag fidelity for DOCX**
   - What we know: Tika maps Word heading styles to HTML heading tags in XHTML output.
   - What's unclear: Does this work for all heading levels (H1-H6) and custom styles?
   - Recommendation: Verify during implementation. If unreliable, fall back to Apache POI XWPF with style checking.

3. **Maximum file size limit**
   - What we know: Tika has configurable size limits on `BodyContentHandler`.
   - What's unclear: What's the practical file size limit for in-process parsing on the target deployment?
   - Recommendation: Set a 10MB default limit. Document that larger files need manual handling or Tika server deployment.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java | Runtime | -- | 17 (project requirement) | -- |
| Maven | Build | -- | -- | -- |
| PostgreSQL | Chunk storage | -- | -- | -- |
| Apache Tika | Document parsing | -- (new dependency) | 3.3.0 | -- |

Note: Availability checks for PostgreSQL and Maven were not performed in this session. The project already runs, so these are assumed available.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (via spring-boot-starter-test) |
| Config file | none — see Wave 0 |
| Quick run command | `mvn test -pl jchatmind` |
| Full suite command | `mvn test -pl jchatmind` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PARSE-01 | TXT file parsing returns content | unit | `mvn test -Dtest=TxtParserServiceImplTest` | -- Wave 0 |
| PARSE-02 | DOCX file parsing preserves heading hierarchy | unit | `mvn test -Dtest=DocxParserServiceImplTest` | -- Wave 0 |
| PARSE-03 | PDF file parsing extracts text | unit | `mvn test -Dtest=PdfParserServiceImplTest` | -- Wave 0 |
| PARSE-04 | XLSX/CSV converts to Markdown tables | unit | `mvn test -Dtest=ExcelParserServiceImplTest` | -- Wave 0 |
| PARSE-05 | MD parsing returns ParsedDocument | unit | `mvn test -Dtest=MarkdownParserServiceImplTest` | -- Wave 0 |
| PARSE-06 | Format routing dispatches to correct parser | unit | `mvn test -Dtest=DocumentFacadeServiceImplTest` | -- Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -pl jchatmind`
- **Per wave merge:** `mvn test -pl jchatmind`
- **Phase gate:** All parser tests green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `jchatmind/src/test/java/com/kama/jchatmind/service/impl/TxtParserServiceImplTest.java` — covers PARSE-01
- [ ] `jchatmind/src/test/java/com/kama/jchatmind/service/impl/DocxParserServiceImplTest.java` — covers PARSE-02
- [ ] `jchatmind/src/test/java/com/kama/jchatmind/service/impl/PdfParserServiceImplTest.java` — covers PARSE-03
- [ ] `jchatmind/src/test/java/com/kama/jchatmind/service/impl/ExcelParserServiceImplTest.java` — covers PARSE-04
- [ ] `jchatmind/src/test/java/com/kama/jchatmind/service/impl/MarkdownParserServiceImplTest.java` — covers PARSE-05
- [ ] `jchatmind/src/test/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImplTest.java` — covers PARSE-06
- [ ] Test fixture files (sample .txt, .docx, .pdf, .xlsx, .csv files) in `src/test/resources/`

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | -- |
| V3 Session Management | no | -- |
| V4 Access Control | yes | Existing auth guards on upload endpoints |
| V5 Input Validation | yes | File type validation, size limits, encoding detection |
| V6 Cryptography | no | -- |

### Known Threat Patterns for Tika

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Malicious file content (XXE, RCE in parsers) | Tampering | Use Tika 3.3.0+ (fixes CVE-2025-66516). Set parsing timeouts and size limits |
| Zip bomb (nested archives in OOXML) | Denial of Service | Tika's `SecureContentHandler` provides zip bomb protection by default in 3.x |
| SSRF via embedded URLs in documents | Information Disclosure | Disable external entity resolution in parser config |
| Large file DoS | Availability | Set `BodyContentHandler` size limit (recommend 10MB) |
| Password-protected files | Availability | Detect and reject with `BizException` (Decision #4) |

## Sources

### Primary (HIGH confidence)
- Maven Central API — `tika-core` 3.3.0 and `tika-parsers-standard-package` 3.3.0 verified via `search.maven.org`
- [Apache Tika Official Site](https://tika.apache.org/) — formats, API documentation, security page
- [Apache Tika GitHub](https://github.com/apache/tika) — CHANGES.txt, source code
- [TIKA-4437 Jira](https://issues.apache.org/jira/browse/TIKA-4437) — DOCX feature extraction improvements (2025)
- [TIKA-2303 Jira](https://issues.apache.org/jira/browse/TIKA-2303) — PDF bookmark extraction
- [Apache Tika 3.3.0 Formats](https://tika.apache.org/3.3.0/formats.html) — supported format list
- Project codebase — existing `DocumentFacadeServiceImpl`, `MarkdownParserServiceImpl`, `BizException`

### Secondary (MEDIUM confidence)
- [Baeldung: Apache Tika](https://www.baeldung.com/apache-tika) — integration patterns
- [StackOverflow: Extracting headings from DOCX](https://stackoverflow.com/questions/29519144/extracting-heading-and-paragraphs-from-doc-and-docx-files-using-apache-poi) — heading extraction approaches
- [Reddit: Tika vs POI/OpenCSV](https://www.reddit.com/r/javahelp/comments/1hugkz4/data_extraction_apache_tika_vs_apache_poi_opencsv/) — structured data extraction consensus
- [PDF Heading Extraction Benchmark (Meuschke et al., 2023)](https://gipplab.uni-goettingen.de/wp-content/papercite-data/pdf/meuschke2023.pdf) — Tika only extracts paragraphs, not headings

### Tertiary (LOW confidence)
- CSDN/51CTO/Zhihu articles on Spring Boot + Tika integration — Chinese-language tutorials, not verified against official docs

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — versions verified via Maven Central API
- Architecture: HIGH — strategy pattern is well-established; code patterns match existing codebase conventions
- Pitfalls: MEDIUM — based on Tika documentation, Jira issues, and community reports; some require runtime verification

**Research date:** 2026-04-23
**Valid until:** 2026-07-23 (30 days — Tika is stable, but new releases may add features)
