package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.dto.ParsedDocument;
import com.kama.jchatmind.service.DocumentParserService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/**
 * Parser dispatcher that routes to the correct parser based on file format.
 */
@Service
@AllArgsConstructor
@Slf4j
public class DocumentParserServiceImpl implements DocumentParserService {

    private final TxtParserService txtParserService;
    private final DocxParserService docxParserService;
    private final PdfParserService pdfParserService;
    private final ExcelParserService excelParserService;
    private final MarkdownParserServiceImpl markdownParserService;

    @Override
    public List<ParsedDocument> parse(InputStream inputStream, String format) {
        return parse(inputStream, format, null);
    }

    @Override
    public List<ParsedDocument> parse(InputStream inputStream, String format, String filename) {
        log.info("解析文件类型: {}", format);

        switch (format.toLowerCase()) {
            case "txt":
                return txtParserService.parse(inputStream);
            case "docx":
                return docxParserService.parse(inputStream);
            case "pdf":
                return pdfParserService.parse(inputStream, filename);
            case "xlsx":
            case "xls":
            case "csv":
                return excelParserService.parse(inputStream, format);
            case "md":
            case "markdown":
                return markdownParserService.parseMarkdown(inputStream);
            default:
                throw new BizException("不支持的文件格式: " + format);
        }
    }
}
