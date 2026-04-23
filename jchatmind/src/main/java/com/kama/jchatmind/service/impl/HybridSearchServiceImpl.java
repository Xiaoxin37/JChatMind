package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.ChunkBgeM3IndexService;
import com.kama.jchatmind.service.ChunkBgeM3IndexService.Bm25Result;
import com.kama.jchatmind.service.HybridSearchService;
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
                                    ChunkBgeM3Mapper chunkBgeM3Mapper) {
        this.bm25IndexService = bm25IndexService;
        this.ragService = ragService;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
    }

    @Override
    public List<HybridResult> search(String kbId, String query, int topK) {
        log.info("混合检索: kbId={}, query={}, topK={}", kbId, query, topK);

        // Step 1: BM25 search
        List<Bm25Result> bm25Results;
        try {
            bm25Results = bm25IndexService.search(query, bm25TopK);
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

        // Step 4: Rerank (if enabled) and return final topK
        if (rerankingEnabled && !rrfResults.isEmpty()) {
            return applyReranking(query, rrfResults, topK);
        }

        // Return topK from RRF results (no reranking)
        return rrfResults.stream().limit(topK).collect(Collectors.toList());
    }

    /**
     * Apply reranking on RRF-fused candidates.
     * Takes top-N candidates, reranks by cosine similarity, returns final top-K.
     */
    private List<HybridResult> applyReranking(String query, List<HybridResult> candidates, int topK) {
        List<String> contents = candidates.stream()
                .map(HybridResult::content)
                .collect(Collectors.toList());

        List<RerankResult> reranked;
        try {
            reranked = ragService.rerank(query, contents);
        } catch (Exception e) {
            log.warn("重排序失败，返回 RRF 结果", e);
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }

        // Map reranked scores back to hybrid results
        Map<Integer, Double> rerankScoreMap = new HashMap<>();
        for (RerankResult rr : reranked) {
            rerankScoreMap.put(rr.originalIndex, rr.score);
        }

        // Build reranked hybrid results, sorted by rerank score descending
        List<HybridResult> rerankedResults = new ArrayList<>();
        for (RerankResult rr : reranked) {
            if (rr.originalIndex >= 0 && rr.originalIndex < candidates.size()) {
                HybridResult original = candidates.get(rr.originalIndex);
                rerankedResults.add(new HybridResult(
                        original.chunkId,
                        original.content,
                        original.metadata,
                        rr.score  // replace RRF score with rerank score
                ));
            }
        }

        // Return final top-K
        List<HybridResult> finalResults = rerankedResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

        log.info("重排序完成: 候选数={}, 最终结果={}", candidates.size(), finalResults.size());
        return finalResults;
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
}
