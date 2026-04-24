package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.ParsedDocument;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkingServiceImplTest {

    @Test
    void preservesSentencePunctuationWhenSplitting() {
        ChunkingServiceImpl service = createService(6, 0.0);
        ParsedDocument doc = new ParsedDocument(
                "title",
                "这是第一句。这是第二句。这是第三句。",
                List.of("title"),
                "txt"
        );

        List<String> chunks = service.chunk(doc, 6);

        assertTrue(chunks.size() >= 2);
        for (String chunk : chunks) {
            assertFalse(chunk.isBlank());
            assertTrue(chunk.endsWith("。") || chunk.endsWith("！") || chunk.endsWith("？"));
            assertTrue(service.estimateTokens(chunk) <= 6);
        }
    }

    @Test
    void enforcesHardLimitForOversizedSentenceWithoutBoundaries() {
        ChunkingServiceImpl service = createService(4, 0.0);
        ParsedDocument doc = new ParsedDocument(
                "title",
                "abcdefghijabcdefghijabcdefghij",
                List.of("title"),
                "txt"
        );

        List<String> chunks = service.chunk(doc, 4);

        assertTrue(chunks.size() >= 2);
        for (String chunk : chunks) {
            assertFalse(chunk.isBlank());
            assertTrue(service.estimateTokens(chunk) <= 4);
        }
    }

    @Test
    void keepsOverlapWithinTokenBudget() {
        ChunkingServiceImpl service = createService(6, 0.5);
        ParsedDocument doc = new ParsedDocument(
                "title",
                "这是第一句。这是第二句。\n\n这是第三句。这是第四句。",
                List.of("title"),
                "txt"
        );

        List<String> chunks = service.chunk(doc, 6);

        assertTrue(chunks.size() >= 2);
        boolean foundOverlap = false;
        for (int i = 1; i < chunks.size(); i++) {
            String current = chunks.get(i);
            assertTrue(service.estimateTokens(current) <= 6);
            if (current.contains("这是第二句。") || current.contains("这是第三句。")) {
                foundOverlap = true;
            }
        }
        assertTrue(foundOverlap);
    }

    @Test
    void splitsByDocumentLocalHeadingsWithoutDomainSpecificDictionary() {
        ChunkingServiceImpl service = createService(80, 0.0);
        ParsedDocument doc = new ParsedDocument(
                "title",
                """
                        系统概述
                        这个系统用于管理知识库、智能体和聊天会话。

                        1. 部署步骤
                        先启动 PostgreSQL，再启动后端服务，最后启动前端页面。

                        风险说明：
                        如果模型密钥配置错误，聊天调用会失败。
                        """,
                List.of("title"),
                "txt"
        );

        List<String> chunks = service.chunk(doc, 80);

        assertTrue(chunks.size() >= 3);
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.startsWith("系统概述")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.startsWith("1. 部署步骤")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.startsWith("风险说明：")));
    }

    @Test
    void treatsShortStandaloneLinesAsContentDefinedSections() {
        ChunkingServiceImpl service = createService(80, 0.0);
        ParsedDocument doc = new ParsedDocument(
                "title",
                """
                        教育背景
                        北京交通大学 软件工程 本科。

                        项目经历
                        负责 RAG 检索、文档解析和向量索引。
                        """,
                List.of("title"),
                "txt"
        );

        List<String> chunks = service.chunk(doc, 80);

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.startsWith("教育背景")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.startsWith("项目经历")));
    }

    private ChunkingServiceImpl createService(int maxTokens, double overlapRatio) {
        ChunkingServiceImpl service = new ChunkingServiceImpl();
        ReflectionTestUtils.setField(service, "maxTokens", maxTokens);
        ReflectionTestUtils.setField(service, "overlapRatio", overlapRatio);
        return service;
    }
}
