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
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCX file parser using Apache Tika.
 * - Parses to XHTML to extract h1-h6 heading tags
 * - Each heading starts a new ParsedDocument section
 * - Content between headings accumulated as section content
 */
@Service
@Slf4j
public class DocxParserService {

    // Pattern to match HTML heading tags from Tika XHTML output
    private static final Pattern HEADING_PATTERN = Pattern.compile("<h([1-6])[^>]*>(.*?)</h\\1>", Pattern.DOTALL);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    public List<ParsedDocument> parse(InputStream inputStream) {
        try {
            // Use BodyContentHandler with 10MB limit
            BodyContentHandler handler = new BodyContentHandler(10 * 1024 * 1024);
            Metadata metadata = new Metadata();
            ParseContext parseContext = new ParseContext();
            Parser parser = new AutoDetectParser();

            parser.parse(inputStream, handler, metadata, parseContext);
            String xhtml = handler.toString();

            if (xhtml == null || xhtml.trim().isEmpty()) {
                return Collections.emptyList();
            }

            return parseHeadings(xhtml);
        } catch (org.apache.tika.exception.TikaException e) {
            log.error("DOCX 文件解析失败", e);
            throw new BizException("DOCX 文件已损坏或格式不支持");
        } catch (Exception e) {
            log.error("DOCX 文件解析异常", e);
            throw new BizException("DOCX 文件已损坏或格式不支持");
        }
    }

    private List<ParsedDocument> parseHeadings(String xhtml) {
        List<ParsedDocument> sections = new ArrayList<>();

        // Find all heading tags and extract text between them
        Matcher matcher = HEADING_PATTERN.matcher(xhtml);
        List<HeadingSegment> segments = new ArrayList<>();

        int lastEnd = 0;
        while (matcher.find()) {
            // Save content before this heading
            if (matcher.start() > lastEnd) {
                String betweenContent = xhtml.substring(lastEnd, matcher.start());
                // Remove HTML tags
                betweenContent = TAG_PATTERN.matcher(betweenContent).replaceAll("").trim();
                if (!betweenContent.isEmpty()) {
                    // Append to last segment if exists
                    if (!segments.isEmpty()) {
                        HeadingSegment last = segments.get(segments.size() - 1);
                        last.content.append("\n").append(betweenContent);
                    }
                }
            }

            int level = Integer.parseInt(matcher.group(1));
            String title = TAG_PATTERN.matcher(matcher.group(2)).replaceAll("").trim();
            segments.add(new HeadingSegment(level, title));
            lastEnd = matcher.end();
        }

        // Handle remaining content after last heading
        if (lastEnd < xhtml.length()) {
            String remaining = TAG_PATTERN.matcher(xhtml.substring(lastEnd)).replaceAll("").trim();
            if (!remaining.isEmpty() && !segments.isEmpty()) {
                segments.get(segments.size() - 1).content.append("\n").append(remaining);
            }
        }

        // Convert segments to ParsedDocuments
        for (HeadingSegment seg : segments) {
            sections.add(new ParsedDocument(
                    seg.title,
                    seg.content.toString().trim(),
                    Collections.emptyList(),
                    "docx"
            ));
        }

        // If no headings found, treat entire text as single section
        if (sections.isEmpty()) {
            String plainText = TAG_PATTERN.matcher(xhtml).replaceAll("").trim();
            if (!plainText.isEmpty()) {
                sections.add(new ParsedDocument("文档内容", plainText, Collections.emptyList(), "docx"));
            }
        }

        return sections;
    }

    private static class HeadingSegment {
        int level;
        String title;
        StringBuilder content = new StringBuilder();

        HeadingSegment(int level, String title) {
            this.level = level;
            this.title = title;
        }
    }
}
