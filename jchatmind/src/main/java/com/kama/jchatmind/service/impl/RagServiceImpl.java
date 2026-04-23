package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RagServiceImpl implements RagService {

    // 封装本地的模型调用
    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Value("${rag.reranking.model:bge-reranker-v2-m3}")
    private String rerankModel;

    @Value("${rag.reranking.timeout-seconds:5}")
    private int rerankTimeout;

    public RagServiceImpl(WebClient.Builder builder, ChunkBgeM3Mapper chunkBgeM3Mapper) {
        this.webClient = builder.baseUrl("http://localhost:11434").build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    private float[] doEmbed(String text) {
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", "bge-m3",
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Embedding response cannot be null");
        return resp.getEmbedding();
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        return similaritySearch(kbId, title, 3);
    }

    @Override
    public List<String> similaritySearch(String kbId, String query, int topK) {
        String queryEmbedding = toPgVector(doEmbed(query));
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, topK);
        return chunks.stream().map(ChunkBgeM3::getContent).toList();
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }

        // Step 1: Embed the query
        float[] queryEmbedding;
        try {
            queryEmbedding = doEmbedForRerank(query);
        } catch (Exception e) {
            log.warn("Rerank failed: cannot embed query, returning original order", e);
            return fallbackResults(documents.size());
        }

        // Step 2: For each document, embed (query + "\n\n" + doc) pair and compute cosine similarity
        List<RerankResult> results = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            String docContent = documents.get(i);
            String pairText = query + "\n\n" + docContent;

            float[] pairEmbedding;
            try {
                pairEmbedding = doEmbedForRerank(pairText);
            } catch (Exception e) {
                log.warn("Rerank failed for document index {}, skipping", i, e);
                results.add(new RerankResult(i, 0.0));
                continue;
            }

            double similarity = cosineSimilarity(queryEmbedding, pairEmbedding);
            results.add(new RerankResult(i, similarity));
        }

        // Step 3: Sort by score descending
        results.sort((a, b) -> Double.compare(b.score, a.score));
        return results;
    }

    private float[] doEmbedForRerank(String text) {
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", rerankModel,
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .timeout(Duration.ofSeconds(rerankTimeout))
                .block();
        Assert.notNull(resp, "Embedding response cannot be null");
        return resp.getEmbedding();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<RerankResult> fallbackResults(int size) {
        List<RerankResult> results = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            results.add(new RerankResult(i, 0.0));
        }
        return results;
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
