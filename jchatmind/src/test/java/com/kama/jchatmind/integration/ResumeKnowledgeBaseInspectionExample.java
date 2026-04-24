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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual inspection example for one real resume PDF.
 *
 * Run only when you want to inspect this fixture:
 * ./mvnw -Dtest=ResumeKnowledgeBaseInspectionExample test
 */
@SpringBootTest
class ResumeKnowledgeBaseInspectionExample {

    private static final Path RESUME_PATH = Path.of("..", "应聘XX岗位_陈俊豪_北京交通大学_18379479378_副本.pdf");

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
    void inspectResumeChunkingAndRetrieval() throws Exception {
        assertTrue(Files.exists(RESUME_PATH), "Resume PDF should exist: " + RESUME_PATH.toAbsolutePath());

        kbId = createKnowledgeBase();
        CreateDocumentResponse uploaded = uploadResume(kbId);

        waitUntilReady(uploaded.getDocumentId());
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.selectByDocId(uploaded.getDocumentId());
        printChunks(chunks);

        assertFalse(chunks.isEmpty(), "Resume upload should produce at least one chunk");
        assertTrue(chunks.size() >= 4, "Resume section-aware chunking should split the page into multiple sections");
        assertTrue(chunks.size() <= 8, "A one-page resume should still produce a manageable number of chunks");

        String joinedChunks = joinContent(chunks);
        assertContainsAny(joinedChunks, "extracted resume text", "陈俊豪", "北京交通大学", "18379479378");

        assertQueryFinds("候选人姓名 陈俊豪", "陈俊豪");
        assertQueryFinds("候选人毕业院校 北京交通大学", "北京交通大学");
        assertQueryFinds("候选人手机号 18379479378", "18379479378");
    }

    private String createKnowledgeBase() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("resume-inspection-" + System.currentTimeMillis());
        request.setDescription("Manual resume chunking and retrieval inspection");
        CreateKnowledgeBaseResponse response = knowledgeBaseFacadeService.createKnowledgeBase(request);
        return response.getKnowledgeBaseId();
    }

    private CreateDocumentResponse uploadResume(String kbId) throws Exception {
        byte[] bytes = Files.readAllBytes(RESUME_PATH);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                RESUME_PATH.getFileName().toString(),
                "application/pdf",
                bytes
        );
        return documentFacadeService.uploadDocument(kbId, file);
    }

    private void waitUntilReady(String documentId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
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
            Thread.sleep(500);
        }
        throw new AssertionError("Document did not become READY in time: " + documentId + ", lastStatus=" + lastStatus);
    }

    private void printChunks(List<ChunkBgeM3> chunks) {
        System.out.println("\n=== Resume chunks ===");
        for (int i = 0; i < chunks.size(); i++) {
            ChunkBgeM3 chunk = chunks.get(i);
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            System.out.printf(
                    "Chunk %d/%d id=%s chars=%d metadata=%s%n%s%n---%n",
                    i + 1,
                    chunks.size(),
                    chunk.getId(),
                    content.length(),
                    chunk.getMetadata(),
                    preview(content)
            );
        }
    }

    private void assertQueryFinds(String query, String expectedTerm) {
        SearchTrace trace = hybridSearchService.searchWithTrace(kbId, query, 5);
        printStage(query, "BM25 recall top-k", trace.bm25Results);
        printStage(query, "Vector recall top-k", trace.vectorResults);
        printStage(query, "RRF fused candidates", trace.rrfResults);
        printStage(query, "Final reranked top-k", trace.finalResults);

        List<StageResult> results = trace.finalResults;
        assertFalse(results.isEmpty(), "Query should return results: " + query);
        assertTrue(
                results.stream().anyMatch(result -> result.content != null && result.content.contains(expectedTerm)),
                "Query should retrieve content containing '" + expectedTerm + "': " + query
        );
        assertTrue(
                results.get(0).content != null && results.get(0).content.contains(expectedTerm),
                "Expected exact field query to rank matching chunk first: " + query
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

    private void assertContainsAny(String text, String label, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return;
            }
        }
        throw new AssertionError("Expected " + label + " to contain one of: " + String.join(", ", terms));
    }

    private String joinContent(List<ChunkBgeM3> chunks) {
        StringBuilder sb = new StringBuilder();
        for (ChunkBgeM3 chunk : chunks) {
            if (chunk.getContent() != null) {
                sb.append(chunk.getContent()).append('\n');
            }
        }
        return sb.toString();
    }

    private String preview(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }
}
