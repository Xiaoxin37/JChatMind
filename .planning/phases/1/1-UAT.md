---
status: complete
phase: 1-multi-format-document-parser
source: .planning/phases/1/SUMMARY.md
started: 2026-04-23T12:00:00Z
updated: 2026-04-23T12:05:00Z
---

## Current Test

[testing complete]

## Tests

### 1. TXT Parser → ParsedDocument with correct text content
expected: Upload .txt file → TxtParserService reads as UTF-8/GBK, returns ParsedDocument with sourceFormat="txt"
result: pass

### 2. DOCX Parser → heading-based sections
expected: Upload .docx file → Tika extracts h1-h6 headings, each heading starts new ParsedDocument
result: pass

### 3. PDF Parser → extracted text with paragraph splitting
expected: Upload .pdf file → Tika extracts text, splits by double-newline for long docs (>2000 chars)
result: pass

### 4. Excel/CSV Parser → Markdown table content
expected: Upload .xlsx/.csv file → rows converted to Markdown table format with header separator
result: pass

### 5. Markdown Parser → no regression
expected: Upload .md file → same behavior as before, returns ParsedDocument with sourceFormat="md"
result: pass

### 6. Unsupported format → clear error message
expected: Upload unsupported format → BizException("不支持的文件格式: {ext}")
result: pass

### 7. DocumentController upload endpoint works
expected: Existing upload endpoint unchanged, format dispatch happens in DocumentFacadeServiceImpl
result: pass

## Summary

total: 7
passed: 7
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

