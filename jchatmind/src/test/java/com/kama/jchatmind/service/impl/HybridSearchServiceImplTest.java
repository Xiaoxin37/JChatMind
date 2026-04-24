package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.service.ChunkBgeM3IndexService;
import com.kama.jchatmind.service.ChunkBgeM3IndexService.Bm25Result;
import com.kama.jchatmind.service.HybridSearchService.HybridResult;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.RagService.RerankResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class HybridSearchServiceImplTest {

    @Test
    void searchSkipsRerankWhenDisabled() {
        ChunkBgeM3IndexService bm25IndexService = mock(ChunkBgeM3IndexService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        stubReadyDocuments(chunkMapper, documentMapper);

        when(bm25IndexService.search("kb-1", "query", 20)).thenReturn(List.of(
                new Bm25Result("c1", 1.0),
                new Bm25Result("c2", 0.8)
        ));
        when(ragService.embed("query")).thenReturn(new float[]{1.0f, 0.0f});
        when(chunkMapper.similaritySearch(anyString(), anyString(), anyInt())).thenReturn(List.of(
                chunk("c2", "doc-2", "doc-2"),
                chunk("c1", "doc-1", "doc-1")
        ));

        HybridSearchServiceImpl service = new HybridSearchServiceImpl(bm25IndexService, ragService, chunkMapper, documentMapper, new ObjectMapper());
        ReflectionTestUtils.setField(service, "bm25TopK", 20);
        ReflectionTestUtils.setField(service, "vectorTopK", 20);
        ReflectionTestUtils.setField(service, "rrfK", 60);
        ReflectionTestUtils.setField(service, "rerankTopN", 20);
        ReflectionTestUtils.setField(service, "rerankingEnabled", false);

        List<HybridResult> results = service.search("kb-1", "query", 2);

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).chunkId);
        assertEquals("c2", results.get(1).chunkId);
        verify(ragService, never()).rerank(anyString(), anyList());
    }

    @Test
    void searchAppliesRerankOrderAndScores() {
        ChunkBgeM3IndexService bm25IndexService = mock(ChunkBgeM3IndexService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        stubReadyDocuments(chunkMapper, documentMapper);

        when(bm25IndexService.search("kb-1", "query", 20)).thenReturn(List.of(
                new Bm25Result("c1", 1.0),
                new Bm25Result("c2", 0.8)
        ));
        when(ragService.embed("query")).thenReturn(new float[]{1.0f, 0.0f});
        when(chunkMapper.similaritySearch(anyString(), anyString(), anyInt())).thenReturn(List.of(
                chunk("c1", "doc-1", "doc-1"),
                chunk("c2", "doc-2", "doc-2")
        ));
        when(ragService.rerank("query", List.of("doc-1", "doc-2"))).thenReturn(List.of(
                new RerankResult(1, 0.97),
                new RerankResult(0, 0.42)
        ));

        HybridSearchServiceImpl service = new HybridSearchServiceImpl(bm25IndexService, ragService, chunkMapper, documentMapper, new ObjectMapper());
        ReflectionTestUtils.setField(service, "bm25TopK", 20);
        ReflectionTestUtils.setField(service, "vectorTopK", 20);
        ReflectionTestUtils.setField(service, "rrfK", 60);
        ReflectionTestUtils.setField(service, "rerankTopN", 20);
        ReflectionTestUtils.setField(service, "rerankingEnabled", true);

        List<HybridResult> results = service.search("kb-1", "query", 2);

        assertEquals(2, results.size());
        assertEquals("c2", results.get(0).chunkId);
        assertTrue(results.get(0).rrfScore > results.get(1).rrfScore);
        assertEquals("c1", results.get(1).chunkId);
    }

    @Test
    void searchFallsBackToRrfWhenRerankThrows() {
        ChunkBgeM3IndexService bm25IndexService = mock(ChunkBgeM3IndexService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        stubReadyDocuments(chunkMapper, documentMapper);

        when(bm25IndexService.search("kb-1", "query", 20)).thenReturn(List.of(
                new Bm25Result("c1", 1.0),
                new Bm25Result("c2", 0.8)
        ));
        when(ragService.embed("query")).thenReturn(new float[]{1.0f, 0.0f});
        when(chunkMapper.similaritySearch(anyString(), anyString(), anyInt())).thenReturn(List.of(
                chunk("c1", "doc-1", "doc-1"),
                chunk("c2", "doc-2", "doc-2")
        ));
        when(ragService.rerank("query", List.of("doc-1", "doc-2"))).thenThrow(new RuntimeException("ollama down"));

        HybridSearchServiceImpl service = new HybridSearchServiceImpl(bm25IndexService, ragService, chunkMapper, documentMapper, new ObjectMapper());
        ReflectionTestUtils.setField(service, "bm25TopK", 20);
        ReflectionTestUtils.setField(service, "vectorTopK", 20);
        ReflectionTestUtils.setField(service, "rrfK", 60);
        ReflectionTestUtils.setField(service, "rerankTopN", 20);
        ReflectionTestUtils.setField(service, "rerankingEnabled", true);

        List<HybridResult> results = service.search("kb-1", "query", 2);

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).chunkId);
        assertEquals("c2", results.get(1).chunkId);
        assertTrue(results.get(0).rrfScore > 0.0);
    }

    private static void stubReadyDocuments(ChunkBgeM3Mapper chunkMapper, DocumentMapper documentMapper) {
        when(chunkMapper.selectById("c1")).thenReturn(chunk("c1", "doc-1", "doc-1"));
        when(chunkMapper.selectById("c2")).thenReturn(chunk("c2", "doc-2", "doc-2"));
        when(documentMapper.selectById("doc-1")).thenReturn(readyDocument("doc-1"));
        when(documentMapper.selectById("doc-2")).thenReturn(readyDocument("doc-2"));
    }

    private static Document readyDocument(String id) {
        return Document.builder()
                .id(id)
                .metadata("{\"processingStatus\":\"READY\"}")
                .build();
    }

    private static ChunkBgeM3 chunk(String id, String docId, String content) {
        return ChunkBgeM3.builder()
                .id(id)
                .docId(docId)
                .content(content)
                .metadata("{}")
                .build();
    }
}
