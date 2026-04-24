package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.service.RagService.RerankResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RagServiceImplTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void embedBatchUsesBatchEndpoint() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = "{\"embeddings\":[[1.0,0.0],[0.0,1.0]]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        RagServiceImpl service = new RagServiceImpl(WebClient.builder().baseUrl(baseUrl).build(), mock(ChunkBgeM3Mapper.class));

        List<float[]> embeddings = service.embedBatch(List.of("chunk-1", "chunk-2"));

        assertEquals(1, requestCount.get());
        assertEquals(2, embeddings.size());
        assertEquals(1.0f, embeddings.get(0)[0], 1e-6f);
        assertEquals(1.0f, embeddings.get(1)[1], 1e-6f);
    }

    @Test
    void rerankFallsBackToOriginalOrderWhenAnyDocumentEmbedFails() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embeddings", exchange -> {
            int index = requestCount.incrementAndGet();
            byte[] body;
            int status;
            if (index == 1) {
                status = 200;
                body = "{\"embedding\":[1.0,0.0]}".getBytes(StandardCharsets.UTF_8);
            } else if (index == 2) {
                status = 500;
                body = "{\"error\":\"mock failure\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = "{\"embedding\":[0.5,0.5]}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        RagServiceImpl service = new RagServiceImpl(WebClient.builder().baseUrl(baseUrl).build(), mock(ChunkBgeM3Mapper.class));
        ReflectionTestUtils.setField(service, "rerankModel", "qllama/bge-reranker-v2-m3");
        ReflectionTestUtils.setField(service, "rerankTimeout", 1);

        List<RerankResult> results = service.rerank("query", List.of("doc-1", "doc-2"));

        assertEquals(2, results.size());
        assertEquals(0, results.get(0).originalIndex);
        assertEquals(1, results.get(1).originalIndex);
        assertEquals(1.0, results.get(0).score, 1e-9);
        assertEquals(0.5, results.get(1).score, 1e-9);
    }
}
