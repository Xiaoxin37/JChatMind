package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.service.ChunkBgeM3IndexService;
import com.kama.jchatmind.service.ChunkBgeM3IndexService.Bm25Result;
import com.kama.jchatmind.service.HybridSearchService;
import com.kama.jchatmind.service.HybridSearchService.SearchTrace;
import com.kama.jchatmind.service.HybridSearchService.StageResult;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.RagService.RerankResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid search service using RRF (Reciprocal Rank Fusion).
 * Combines BM25 keyword search with vector similarity search.
 * RRF formula: score(d) = sum(1 / (k + rank_i(d)))
 */
@Service
@Slf4j
public class HybridSearchServiceImpl implements HybridSearchService {

    private final ChunkBgeM3IndexService bm25IndexService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final DocumentMapper documentMapper;
    private final ObjectMapper objectMapper;

    @Value("${rag.retrieval.bm25-top-k:20}")
    private int bm25TopK;

    @Value("${rag.retrieval.vector-top-k:20}")
    private int vectorTopK;

    @Value("${rag.retrieval.rrf-k:60}")
    private int rrfK;

    @Value("${rag.reranking.enabled:true}")
    private boolean rerankingEnabled;

    @Value("${rag.reranking.top-n:20}")
    private int rerankTopN;

    @Value("${rag.retrieval.top-k:5}")
    private int defaultTopK;

    public HybridSearchServiceImpl(ChunkBgeM3IndexService bm25IndexService,
                                    RagService ragService,
                                    ChunkBgeM3Mapper chunkBgeM3Mapper,
                                    DocumentMapper documentMapper,
                                    ObjectMapper objectMapper) {
        this.bm25IndexService = bm25IndexService;
        this.ragService = ragService;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<HybridResult> search(String kbId, String query, int topK) {
        return searchWithTrace(kbId, query, topK).finalResults.stream()
                .map(result -> new HybridResult(result.chunkId, result.content, result.metadata, result.score))
                .collect(Collectors.toList());
    }

    @Override
    public SearchTrace searchWithTrace(String kbId, String query, int topK) {
        log.info("混合检索: kbId={}, query={}, topK={}", kbId, query, topK);

        // Step 1: BM25 search
        List<Bm25Result> bm25Results;
        try {
            bm25Results = bm25IndexService.search(kbId, query, bm25TopK);
            bm25Results = bm25Results.stream()
                    .filter(result -> isReadyChunk(result.chunkId))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("BM25 搜索失败，降级为向量搜索", e);
            bm25Results = Collections.emptyList();
        }

        // Step 2: Vector similarity search
        List<ChunkBgeM3> vectorResults;
        try {
            float[] embedding = ragService.embed(query);
            String vectorLiteral = toPgVector(embedding);
            vectorResults = chunkBgeM3Mapper.similaritySearch(kbId, vectorLiteral, vectorTopK);
        } catch (Exception e) {
            log.warn("向量搜索失败，降级为 BM25 搜索", e);
            vectorResults = Collections.emptyList();
        }

        // Step 3: RRF Fusion - get rerankTopN candidates
        List<HybridResult> rrfResults = rrfFuse(bm25Results, vectorResults, rerankTopN);
        List<StageResult> rrfTrace = toStageResults(rrfResults, "rrf");

        // Step 4: Rerank (if enabled) and return final topK
        List<HybridResult> finalResults;
        if (rerankingEnabled && !rrfResults.isEmpty()) {
            finalResults = applyReranking(query, rrfResults, topK);
        } else {
            finalResults = rrfResults.stream().limit(topK).collect(Collectors.toList());
        }

        return new SearchTrace(
                toBm25StageResults(bm25Results),
                toVectorStageResults(vectorResults),
                rrfTrace,
                toStageResults(finalResults, rerankingEnabled ? "final=rrf+exact+rerank" : "final=rrf")
        );
    }

    /**
     * Apply reranking on RRF-fused candidates.
     * Takes top-N candidates, reranks by cosine similarity, returns final top-K.
     */
    private List<HybridResult> applyReranking(String query, List<HybridResult> candidates, int topK) {
        List<String> contents = candidates.stream()
                .map(result -> result.content)
                .collect(Collectors.toList());

        List<RerankResult> reranked;
        try {
            reranked = ragService.rerank(query, contents);
        } catch (Exception e) {
            log.warn("重排序失败，返回 RRF 结果", e);
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }

        // Map reranked scores back to hybrid results. Rerank is a secondary signal;
        // RRF and exact query-term hits stay in the final score so precise fields
        // like names, schools, and phone numbers are not pushed down by semantic noise.
        Map<Integer, Double> rerankScoreMap = new HashMap<>();
        for (RerankResult rr : reranked) {
            rerankScoreMap.put(rr.originalIndex, rr.score);
        }

        List<HybridResult> rerankedResults = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            HybridResult original = candidates.get(i);
            double rerankScore = rerankScoreMap.getOrDefault(i, 0.0);
            double exactBoost = exactTermBoost(query, original.content);
            double finalScore = original.rrfScore + exactBoost + 0.05 * rerankScore;
            rerankedResults.add(new HybridResult(
                    original.chunkId,
                    original.content,
                    original.metadata,
                    finalScore
            ));
        }
        rerankedResults.sort(Comparator.comparingDouble((HybridResult result) -> result.rrfScore).reversed());

        // Return final top-K
        List<HybridResult> finalResults = rerankedResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

        log.info("重排序完成: 候选数={}, 最终结果={}", candidates.size(), finalResults.size());
        return finalResults;
    }

    private double exactTermBoost(String query, String content) {
        if (query == null || content == null || content.isBlank()) {
            return 0.0;
        }

        List<String> terms = Arrays.stream(query.trim().split("\\s+"))
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .distinct()
                .toList();
        if (terms.isEmpty()) {
            return 0.0;
        }

        int matched = 0;
        int matchedChars = 0;
        for (String term : terms) {
            if (content.contains(term)) {
                matched++;
                matchedChars += term.length();
            }
        }
        if (matched == 0) {
            return 0.0;
        }
        return 0.20 * matched + Math.min(0.20, matchedChars / 100.0);
    }

    /**
     * RRF (Reciprocal Rank Fusion) algorithm.
     * score(d) = sum(1 / (k + rank_i(d))) for each result list i where d appears.
     */
    private List<HybridResult> rrfFuse(List<Bm25Result> bm25Results,
                                        List<ChunkBgeM3> vectorResults,
                                        int topK) {
        // Map chunkId -> RRF score
        Map<String, Double> rrfScores = new LinkedHashMap<>();

        // BM25 scores: rank-based RRF
        for (int i = 0; i < bm25Results.size(); i++) {
            Bm25Result r = bm25Results.get(i);
            double score = 1.0 / (rrfK + (i + 1));
            rrfScores.merge(r.chunkId, score, Double::sum);
        }

        // Vector scores: rank-based RRF
        for (int i = 0; i < vectorResults.size(); i++) {
            ChunkBgeM3 chunk = vectorResults.get(i);
            double score = 1.0 / (rrfK + (i + 1));
            rrfScores.merge(chunk.getId(), score, Double::sum);
        }

        // Sort by RRF score descending
        List<Map.Entry<String, Double>> sorted = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toList());

        // Build hybrid results with content and metadata from DB
        Map<String, ChunkBgeM3> chunkMap = vectorResults.stream()
                .collect(Collectors.toMap(ChunkBgeM3::getId, c -> c, (a, b) -> a));

        List<HybridResult> hybridResults = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sorted) {
            String chunkId = entry.getKey();
            double score = entry.getValue();

            ChunkBgeM3 chunk = chunkMap.get(chunkId);
            if (chunk != null) {
                hybridResults.add(new HybridResult(chunkId, chunk.getContent(), chunk.getMetadata(), score));
            } else {
                // If not in vector results, look up from BM25 side
                // We need to fetch from DB since BM25 results don't carry content
                chunk = chunkBgeM3Mapper.selectById(chunkId);
                if (chunk != null) {
                    hybridResults.add(new HybridResult(chunkId, chunk.getContent(), chunk.getMetadata(), score));
                }
            }
        }

        log.info("RRF 融合完成: BM25结果={}, 向量结果={}, 融合后={}",
                bm25Results.size(), vectorResults.size(), hybridResults.size());
        return hybridResults;
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

    private boolean isReadyChunk(String chunkId) {
        ChunkBgeM3 chunk = chunkBgeM3Mapper.selectById(chunkId);
        return chunk != null && isReadyDocument(chunk.getDocId());
    }

    private boolean isReadyDocument(String docId) {
        Document document = documentMapper.selectById(docId);
        if (document == null) {
            return false;
        }
        try {
            if (document.getMetadata() == null || document.getMetadata().isBlank()) {
                return true;
            }
            DocumentDTO.MetaData metadata = objectMapper.readValue(document.getMetadata(), DocumentDTO.MetaData.class);
            return metadata.getProcessingStatus() == null || "READY".equals(metadata.getProcessingStatus());
        } catch (Exception e) {
            log.warn("读取文档处理状态失败，跳过检索结果: docId={}", docId, e);
            return false;
        }
    }

    private List<StageResult> toBm25StageResults(List<Bm25Result> results) {
        List<StageResult> trace = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            Bm25Result result = results.get(i);
            ChunkBgeM3 chunk = chunkBgeM3Mapper.selectById(result.chunkId);
            trace.add(new StageResult(
                    i + 1,
                    result.chunkId,
                    chunk != null ? chunk.getContent() : "",
                    chunk != null ? chunk.getMetadata() : null,
                    result.score,
                    "bm25"
            ));
        }
        return trace;
    }

    private List<StageResult> toVectorStageResults(List<ChunkBgeM3> results) {
        List<StageResult> trace = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            ChunkBgeM3 chunk = results.get(i);
            trace.add(new StageResult(
                    i + 1,
                    chunk.getId(),
                    chunk.getContent(),
                    chunk.getMetadata(),
                    1.0 / (i + 1),
                    "vector-rank"
            ));
        }
        return trace;
    }

    private List<StageResult> toStageResults(List<HybridResult> results, String reason) {
        List<StageResult> trace = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            HybridResult result = results.get(i);
            trace.add(new StageResult(
                    i + 1,
                    result.chunkId,
                    result.content,
                    result.metadata,
                    result.rrfScore,
                    reason
            ));
        }
        return trace;
    }
}
