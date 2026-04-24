package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.dto.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * PDF file parser using Apache Tika.
 * - Extracts text content via Tika (PDFBox internally)
 * - Uses filename as root title since PDF is a presentation format
 * - Splits by double-newline into paragraphs for long documents
 */
@Service
@Slf4j
public class PdfParserService {

    private static final int CONTENT_SPLIT_THRESHOLD = 2000;
    private static final int MAX_EXTRACTED_TEXT_CHARS = 50 * 1024 * 1024;

    public List<ParsedDocument> parse(InputStream inputStream) {
        return parse(inputStream, null);
    }

    public List<ParsedDocument> parse(InputStream inputStream, String filename) {
        try {
            BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_TEXT_CHARS);
            Metadata metadata = new Metadata();
            ParseContext parseContext = new ParseContext();
            Parser parser = new AutoDetectParser();

            parser.parse(inputStream, handler, metadata, parseContext);
            String text = handler.toString();

            if (text == null || text.trim().isEmpty()) {
                return Collections.emptyList();
            }

            // Use filename (without extension) as root title
            String rootTitle = filename != null && filename.contains(".")
                    ? filename.substring(0, filename.lastIndexOf("."))
                    : "PDF文档";

            if (text.length() > CONTENT_SPLIT_THRESHOLD) {
                // Split by double newline into paragraphs
                String[] paragraphs = text.split("\\n\\n+");
                List<ParsedDocument> sections = new java.util.ArrayList<>();
                for (int i = 0; i < paragraphs.length; i++) {
                    String p = paragraphs[i].trim();
                    if (!p.isEmpty()) {
                        sections.add(new ParsedDocument(
                                rootTitle + " - 段落 " + (i + 1),
                                p,
                                List.of(rootTitle),
                                "pdf"
                        ));
                    }
                }
                return sections;
            }

            // Short document — single section
            return List.of(new ParsedDocument(rootTitle, text.trim(), Collections.emptyList(), "pdf"));
        } catch (Exception e) {
            log.error("PDF 文件解析失败", e);
            throw new BizException("PDF 文件已加密或已损坏，无法解析");
        }
    }
}
