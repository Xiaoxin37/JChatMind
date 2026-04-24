package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.dto.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DOCX file parser using Apache POI paragraph styles.
 * - Reads heading styles to preserve heading hierarchy
 * - Each heading starts a new ParsedDocument section
 * - Content between headings accumulated as section content
 */
@Service
@Slf4j
public class DocxParserService {

    public List<ParsedDocument> parse(InputStream inputStream) {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            return parseParagraphs(document.getParagraphs());
        } catch (IOException e) {
            log.error("DOCX 文件读取失败", e);
            throw new BizException("DOCX 文件已损坏或格式不支持");
        } catch (Exception e) {
            log.error("DOCX 文件解析异常", e);
            throw new BizException("DOCX 文件已损坏或格式不支持");
        }
    }

    private List<ParsedDocument> parseParagraphs(List<XWPFParagraph> paragraphs) {
        List<ParsedDocument> sections = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        String currentTitle = null;
        List<String> currentHierarchy = Collections.emptyList();
        StringBuilder currentContent = new StringBuilder();
        StringBuilder introContent = new StringBuilder();

        for (XWPFParagraph paragraph : paragraphs) {
            String text = normalizeParagraphText(paragraph.getText());
            if (text.isEmpty()) {
                continue;
            }

            Integer headingLevel = resolveHeadingLevel(paragraph);
            if (headingLevel != null) {
                if (currentTitle != null) {
                    sections.add(new ParsedDocument(
                            currentTitle,
                            currentContent.toString().trim(),
                            currentHierarchy,
                            "docx"
                    ));
                }

                while (headingStack.size() >= headingLevel) {
                    headingStack.remove(headingStack.size() - 1);
                }
                headingStack.add(text);

                currentTitle = text;
                currentHierarchy = new ArrayList<>(headingStack);
                currentContent = new StringBuilder();
                if (!introContent.isEmpty()) {
                    currentContent.append(introContent.toString().trim());
                    introContent = new StringBuilder();
                }
                continue;
            }

            if (currentTitle == null) {
                appendLine(introContent, text);
            } else {
                appendLine(currentContent, text);
            }
        }

        if (currentTitle != null) {
            sections.add(new ParsedDocument(
                    currentTitle,
                    currentContent.toString().trim(),
                    currentHierarchy,
                    "docx"
            ));
        }

        if (sections.isEmpty()) {
            String plainText = introContent.toString().trim();
            if (!plainText.isEmpty()) {
                sections.add(new ParsedDocument("文档内容", plainText, Collections.emptyList(), "docx"));
            }
        }

        return sections;
    }

    private Integer resolveHeadingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style != null) {
            String normalized = style.trim().toLowerCase();
            if (normalized.startsWith("heading")) {
                return parseStyleLevel(normalized.substring("heading".length()));
            }
            if (normalized.startsWith("标题")) {
                return parseStyleLevel(normalized.substring("标题".length()));
            }
        }

        if (paragraph.getCTP() != null
                && paragraph.getCTP().getPPr() != null
                && paragraph.getCTP().getPPr().getOutlineLvl() != null) {
            return paragraph.getCTP().getPPr().getOutlineLvl().getVal().intValue() + 1;
        }

        return null;
    }

    private int parseStyleLevel(String suffix) {
        String digits = suffix.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 1;
        }
        return Integer.parseInt(digits);
    }

    private String normalizeParagraphText(String text) {
        return text == null ? "" : text.replace('\u00A0', ' ').trim();
    }

    private void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) {
            builder.append("\n");
        }
        builder.append(line);
    }
}
