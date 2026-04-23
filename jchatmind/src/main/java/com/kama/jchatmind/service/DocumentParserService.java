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

    /**
     * Parse document content with optional filename (used by PDF for title extraction).
     *
     * @param inputStream Document input stream
     * @param format      File format extension
     * @param filename    Original filename (may be null for non-PDF)
     * @return List of parsed document sections
     */
    default List<ParsedDocument> parse(InputStream inputStream, String format, String filename) {
        return parse(inputStream, format);
    }
}
