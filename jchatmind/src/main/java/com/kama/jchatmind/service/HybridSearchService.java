package com.kama.jchatmind.service;

import java.util.List;

/**
 * Hybrid search combining BM25 + vector similarity with RRF fusion.
 */
public interface HybridSearchService {
    /**
     * Execute hybrid search with RRF fusion.
     *
     * @param kbId  knowledge base ID
     * @param query search query
     * @param topK  final result count
     * @return hybrid ranked results with RRF scores
     */
    List<HybridResult> search(String kbId, String query, int topK);

    /**
     * Hybrid search result with RRF score.
     */
    class HybridResult {
        public final String chunkId;
        public final String content;
        public final String metadata;
        public final double rrfScore;

        public HybridResult(String chunkId, String content, String metadata, double rrfScore) {
            this.chunkId = chunkId;
            this.content = content;
            this.metadata = metadata;
            this.rrfScore = rrfScore;
        }
    }
}
