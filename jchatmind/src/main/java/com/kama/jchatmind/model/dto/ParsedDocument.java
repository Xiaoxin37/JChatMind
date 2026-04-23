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
