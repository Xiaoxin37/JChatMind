package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ParsedDocument;

import java.io.InputStream;
import java.util.List;

/**
 * Markdown 解析服务接口
 */
public interface MarkdownParserService {
    /**
     * 解析 Markdown 文件，提取标题和对应的内容
     *
     * @param inputStream Markdown 文件输入流
     * @return 解析后的文档列表
     */
    List<ParsedDocument> parseMarkdown(InputStream inputStream);
}
