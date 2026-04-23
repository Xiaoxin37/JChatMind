package com.kama.jchatmind.integration;

import com.kama.jchatmind.model.request.CreateKnowledgeBaseRequest;
import com.kama.jchatmind.model.response.CreateKnowledgeBaseResponse;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.HybridSearchService;
import com.kama.jchatmind.service.KnowledgeBaseFacadeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class E2EIntegrationTests {

    @Autowired
    private DocumentFacadeService documentFacadeService;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private KnowledgeBaseFacadeService kbFacadeService;

    private String testKbId;

    @BeforeEach
    void setUp() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("e2e-test-" + System.currentTimeMillis());
        request.setDescription("E2E integration test knowledge base");
        CreateKnowledgeBaseResponse response = kbFacadeService.createKnowledgeBase(request);
        testKbId = response.getKnowledgeBaseId();
    }

    @AfterEach
    void tearDown() {
        if (testKbId != null) {
            try {
                kbFacadeService.deleteKnowledgeBase(testKbId);
            } catch (Exception e) {
                // Best-effort cleanup
            }
        }
    }

    private MockMultipartFile createTextFile(String filename, String mimeType, String content) {
        return new MockMultipartFile(filename, filename, mimeType, content.getBytes());
    }

    private MockMultipartFile createPdfFile() throws Exception {
        byte[] pdfContent = ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                + "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n"
                + "4 0 obj\n<< /Length 62 >>\nstream\nBT\n/F1 12 Tf\n100 700 Td\n"
                + "(Apache Kafka is a distributed event streaming platform for building data pipelines.) Tj\n"
                + "ET\nendstream\nendobj\n"
                + "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
                + "xref\n0 6\ntrailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n0\n%%EOF").getBytes();
        return new MockMultipartFile("test-pipeline.pdf", "test-pipeline.pdf", "application/pdf", pdfContent);
    }

    private MockMultipartFile createDocxFile(String textContent) throws Exception {
        byte[] docxBytes = createMinimalDocx(textContent);
        return new MockMultipartFile("test-pipeline.docx", "test-pipeline.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docxBytes);
    }

    private byte[] createMinimalDocx(String textContent) throws Exception {
        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">\n"
                + "  <w:body>\n"
                + "    <w:p><w:r><w:t>" + escapeXml(textContent) + "</w:t></w:r></w:p>\n"
                + "  </w:body>\n"
                + "</w:document>";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n"
                    + "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n"
                    + "  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n"
                    + "  <Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>\n"
                    + "</Types>".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(documentXml.getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    // === E2E-01: Per-format upload-parse-search tests ===

    @Test
    void test01_fullPipeline_txtFile() throws Exception {
        String content = "Spring Boot is a popular Java framework for building microservices and REST APIs";
        MockMultipartFile file = createTextFile("test-pipeline.txt", "text/plain", content);

        CreateDocumentResponse uploadResponse = documentFacadeService.uploadDocument(testKbId, file);
        assertNotNull(uploadResponse.getDocumentId(), "Upload should return document ID");

        Thread.sleep(2000);

        var results = hybridSearchService.search(testKbId, "Java framework microservices", 5);
        assertFalse(results.isEmpty(), "Search should return results for uploaded TXT content");
        assertTrue(results.stream().anyMatch(r -> r.content.contains("Spring Boot") || r.content.contains("Java")),
                "Results should contain Spring Boot or Java references");
    }

    @Test
    void test02_fullPipeline_pdfFile() throws Exception {
        MockMultipartFile file = createPdfFile();

        CreateDocumentResponse uploadResponse = documentFacadeService.uploadDocument(testKbId, file);
        assertNotNull(uploadResponse.getDocumentId(), "Upload should return document ID");

        Thread.sleep(2000);

        var results = hybridSearchService.search(testKbId, "distributed event streaming", 5);
        assertFalse(results.isEmpty(), "Search should return results for uploaded PDF content");
        assertTrue(results.stream().anyMatch(r -> r.content.contains("Kafka") || r.content.contains("event streaming")),
                "Results should contain Kafka or event streaming references");
    }

    @Test
    void test03_fullPipeline_docxFile() throws Exception {
        MockMultipartFile file = createDocxFile("Redis is an in-memory data store used for caching and real-time analytics");

        CreateDocumentResponse uploadResponse = documentFacadeService.uploadDocument(testKbId, file);
        assertNotNull(uploadResponse.getDocumentId(), "Upload should return document ID");

        Thread.sleep(2000);

        var results = hybridSearchService.search(testKbId, "in-memory data store caching", 5);
        assertFalse(results.isEmpty(), "Search should return results for uploaded DOCX content");
        assertTrue(results.stream().anyMatch(r -> r.content.contains("Redis") || r.content.contains("in-memory")),
                "Results should contain Redis or in-memory references");
    }

    @Test
    void test04_fullPipeline_csvFile() throws Exception {
        String content = "name,description,value\nCPU,Processor speed,3.5GHz\nRAM,Memory capacity,16GB";
        MockMultipartFile file = createTextFile("test-pipeline.csv", "text/csv", content);

        CreateDocumentResponse uploadResponse = documentFacadeService.uploadDocument(testKbId, file);
        assertNotNull(uploadResponse.getDocumentId(), "Upload should return document ID");

        Thread.sleep(2000);

        var results = hybridSearchService.search(testKbId, "processor speed memory", 5);
        assertFalse(results.isEmpty(), "Search should return results for uploaded CSV content");
        assertTrue(results.stream().anyMatch(r -> r.content.contains("Processor") || r.content.contains("Memory")),
                "Results should contain CPU/RAM table content");
    }

    // === E2E-02: Hybrid search + rerank quality test ===

    @Test
    void test05_hybridSearchReturnsRerankedResults() throws Exception {
        String content = "Machine learning is a subset of artificial intelligence that focuses on algorithms that learn from data. "
                + "Deep learning is a subset of machine learning using neural networks with multiple layers. "
                + "Natural language processing enables machines to understand and generate human text.";
        MockMultipartFile file = createTextFile("ml-content.txt", "text/plain", content);

        documentFacadeService.uploadDocument(testKbId, file);
        Thread.sleep(2000);

        var results = hybridSearchService.search(testKbId, "neural networks deep learning AI", 5);

        assertFalse(results.isEmpty(), "Hybrid search should return non-empty results");
        for (var result : results) {
            assertNotNull(result.chunkId, "Each result should have a chunkId");
            assertNotNull(result.content, "Each result should have content");
            assertTrue(result.rrfScore > 0, "Each result should have a positive RRF score, got: " + result.rrfScore);
        }

        boolean containsRelevant = results.stream().anyMatch(r ->
                r.content.contains("deep learning") || r.content.contains("neural networks") || r.content.contains("machine learning"));
        assertTrue(containsRelevant, "At least one result should contain deep learning, neural networks, or machine learning");
    }

    @Test
    void test06_knowledgeToolReturnsContent() throws Exception {
        String content = "Docker is a platform for containerizing applications. "
                + "Kubernetes orchestrates containerized applications across clusters of hosts.";
        MockMultipartFile file = createTextFile("devops-content.txt", "text/plain", content);

        documentFacadeService.uploadDocument(testKbId, file);
        Thread.sleep(2000);

        var results = hybridSearchService.search(testKbId, "container orchestration", 3);
        assertFalse(results.isEmpty(), "Search should return results for devops content");

        String joinedContent = results.stream()
                .map(r -> r.content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(joinedContent.isEmpty(), "Joined content should not be empty");
        assertTrue(joinedContent.contains("container") || joinedContent.contains("Kubernetes"),
                "Result content should contain container or Kubernetes references");
    }
}
