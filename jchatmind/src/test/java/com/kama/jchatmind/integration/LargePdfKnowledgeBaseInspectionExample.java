package com.kama.jchatmind.integration;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.request.CreateKnowledgeBaseRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.CreateKnowledgeBaseResponse;
import com.kama.jchatmind.model.vo.DocumentVO;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.HybridSearchService;
import com.kama.jchatmind.service.HybridSearchService.SearchTrace;
import com.kama.jchatmind.service.HybridSearchService.StageResult;
import com.kama.jchatmind.service.KnowledgeBaseFacadeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual inspection example for a large real PDF.
 *
 * Run only when you want to inspect this fixture:
 * ./mvnw -Dtest=LargePdfKnowledgeBaseInspectionExample test
 */
@SpringBootTest(properties = {
        "rag.chunking.max-tokens=220",
        "rag.chunking.overlap-ratio=0.12"
})
class LargePdfKnowledgeBaseInspectionExample {

    private static final Path LARGE_PDF_PATH = Path.of("..", "面渣逆袭并发编程篇V2.1.pdf");

    @Autowired
    private DocumentFacadeService documentFacadeService;

    @Autowired
    private KnowledgeBaseFacadeService knowledgeBaseFacadeService;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    private String kbId;

    @AfterEach
    void tearDown() {
        if (kbId != null) {
            try {
                knowledgeBaseFacadeService.deleteKnowledgeBase(kbId);
            } catch (Exception ignored) {
                // Keep this inspection example focused on retrieval behavior.
            }
        }
    }

    @Test
    void inspectLargePdfChunkingAndRetrieval() throws Exception {
        assertTrue(Files.exists(LARGE_PDF_PATH), "Large PDF should exist: " + LARGE_PDF_PATH.toAbsolutePath());

        kbId = createKnowledgeBase();
        CreateDocumentResponse uploaded = uploadLargePdf(kbId);
        waitUntilReady(uploaded.getDocumentId(), 10 * 60_000);

        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.selectByDocId(uploaded.getDocumentId());
        printChunkSummary(chunks);
        printRepresentativeChunks(chunks);

        assertTrue(chunks.size() >= 50, "Large PDF should be split into many chunks for granular retrieval");
        assertTrue(
                chunks.stream().map(ChunkBgeM3::getContent).anyMatch(content -> containsAny(content, "AQS", "volatile", "线程池", "ThreadLocal")),
                "Extracted chunks should contain expected concurrent-programming terms"
        );

        assertQueryFinds("AQS 是什么", "AQS");
        assertQueryFinds("volatile 如何保证可见性", "volatile", "可见性");
        assertQueryFinds("线程池 核心参数", "线程池");
        assertQueryFinds("ThreadLocal 内存泄漏", "ThreadLocal");
    }

    private String createKnowledgeBase() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("large-pdf-inspection-" + System.currentTimeMillis());
        request.setDescription("Manual large PDF chunking and retrieval inspection");
        CreateKnowledgeBaseResponse response = knowledgeBaseFacadeService.createKnowledgeBase(request);
        return response.getKnowledgeBaseId();
    }

    private CreateDocumentResponse uploadLargePdf(String kbId) throws Exception {
        byte[] bytes = Files.readAllBytes(LARGE_PDF_PATH);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                LARGE_PDF_PATH.getFileName().toString(),
                "application/pdf",
                bytes
        );
        return documentFacadeService.uploadDocument(kbId, file);
    }

    private void waitUntilReady(String documentId, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String lastStatus = "UNKNOWN";
        while (System.currentTimeMillis() < deadline) {
            for (DocumentVO document : documentFacadeService.getDocumentsByKbId(kbId).getDocuments()) {
                if (documentId.equals(document.getId())) {
                    lastStatus = document.getStatus();
                    if ("READY".equals(lastStatus)) {
                        return;
                    }
                    if ("FAILED".equals(lastStatus)) {
                        throw new AssertionError("Document processing failed: " + documentId);
                    }
                }
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Document did not become READY in time: " + documentId + ", lastStatus=" + lastStatus);
    }

    private void assertQueryFinds(String query, String... expectedTerms) {
        SearchTrace trace = hybridSearchService.searchWithTrace(kbId, query, 8);
        printStage(query, "BM25 recall top-k", trace.bm25Results);
        printStage(query, "Vector recall top-k", trace.vectorResults);
        printStage(query, "RRF fused candidates", trace.rrfResults);
        printStage(query, "Final reranked top-k", trace.finalResults);

        List<StageResult> results = trace.finalResults;
        assertFalse(results.isEmpty(), "Query should return results: " + query);
        assertTrue(
                results.stream().anyMatch(result -> containsAny(result.content, expectedTerms)),
                "Query should retrieve content containing one of expected terms: " + query
        );
    }

    private void printChunkSummary(List<ChunkBgeM3> chunks) {
        System.out.println("\n=== Large PDF chunk summary ===");
        System.out.println("File: " + LARGE_PDF_PATH.getFileName());
        System.out.println("Total chunks: " + chunks.size());

        IntSummaryStatistics stats = chunks.stream()
                .map(ChunkBgeM3::getContent)
                .map(content -> content == null ? "" : content)
                .mapToInt(String::length)
                .summaryStatistics();
        System.out.printf(
                "Chunk chars: min=%d avg=%.1f max=%d%n",
                stats.getMin(),
                stats.getAverage(),
                stats.getMax()
        );

        chunks.stream()
                .max(Comparator.comparingInt(chunk -> safeContent(chunk).length()))
                .ifPresent(chunk -> System.out.printf("Longest chunk id=%s chars=%d%n", chunk.getId(), safeContent(chunk).length()));
    }

    private void printRepresentativeChunks(List<ChunkBgeM3> chunks) {
        System.out.println("\n=== Representative chunks ===");
        int limit = Math.min(chunks.size(), 12);
        for (int i = 0; i < limit; i++) {
            printChunk(i, chunks.size(), chunks.get(i));
        }
    }

    private void printChunk(int index, int total, ChunkBgeM3 chunk) {
        String content = safeContent(chunk);
        System.out.printf(
                "Chunk %d/%d id=%s chars=%d metadata=%s%n%s%n---%n",
                index + 1,
                total,
                chunk.getId(),
                content.length(),
                chunk.getMetadata(),
                preview(content)
        );
    }

    private void printStage(String query, String stage, List<StageResult> results) {
        System.out.println("\n=== Query: " + query + " | " + stage + " ===");
        for (StageResult result : results) {
            System.out.printf(
                    "Rank %d id=%s score=%.6f reason=%s%n%s%n---%n",
                    result.rank,
                    result.chunkId,
                    result.score,
                    result.reason,
                    preview(result.content)
            );
        }
    }

    private boolean containsAny(String text, String... terms) {
        if (text == null) {
            return false;
        }
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String safeContent(ChunkBgeM3 chunk) {
        return chunk.getContent() == null ? "" : chunk.getContent();
    }

    private String preview(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }
}
