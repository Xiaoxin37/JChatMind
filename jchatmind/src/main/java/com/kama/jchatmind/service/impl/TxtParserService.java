package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.dto.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TXT file parser.
 * - Reads as UTF-8, falls back to GBK if decode fails (common for Chinese text)
 * - Detects Markdown-like headings (# heading) and splits by them
 * - Otherwise treats entire file as single section
 */
@Service
@Slf4j
public class TxtParserService {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+.*$", Pattern.MULTILINE);

    public List<ParsedDocument> parse(InputStream inputStream) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            String text = decodeText(bytes);

            if (text == null || text.trim().isEmpty()) {
                return Collections.emptyList();
            }

            // Check if content contains Markdown-like headings
            Matcher matcher = MARKDOWN_HEADING.matcher(text);
            if (matcher.find()) {
                return parseWithHeadings(text);
            }

            // No Markdown headings — treat entire file as single section
            return List.of(new ParsedDocument("文档内容", text, Collections.emptyList(), "txt"));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("TXT 文件解析失败", e);
            throw new BizException("TXT 文件编码无法识别，请尝试保存为 UTF-8 格式");
        }
    }

    private String decodeText(byte[] bytes) {
        // Try UTF-8 first
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Fallback to GBK
        }
        try {
            return new String(bytes, Charset.forName("GBK"));
        } catch (Exception e) {
            log.error("无法识别 TXT 文件编码", e);
            throw new BizException("TXT 文件编码无法识别，请尝试保存为 UTF-8 格式");
        }
    }

    private List<ParsedDocument> parseWithHeadings(String text) {
        String[] lines = text.split("\n", -1);
        List<ParsedDocument> sections = new java.util.ArrayList<>();
        StringBuilder currentContent = new StringBuilder();
        String currentTitle = null;

        Pattern headingPattern = Pattern.compile("^(#{1,6})\\s+(.+)$");

        for (String line : lines) {
            Matcher matcher = headingPattern.matcher(line);
            if (matcher.matches()) {
                // Save previous section
                if (currentTitle != null) {
                    sections.add(new ParsedDocument(currentTitle, currentContent.toString().trim(),
                            Collections.emptyList(), "txt"));
                }
                currentTitle = matcher.group(2).trim();
                currentContent = new StringBuilder();
            } else {
                if (currentTitle != null) {
                    if (currentContent.length() > 0) {
                        currentContent.append("\n");
                    }
                    currentContent.append(line);
                }
            }
        }

        // Save last section
        if (currentTitle != null) {
            sections.add(new ParsedDocument(currentTitle, currentContent.toString().trim(),
                    Collections.emptyList(), "txt"));
        }

        return sections;
    }
}
