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
     * Execute hybrid search and expose each ranking stage for diagnostics.
     */
    SearchTrace searchWithTrace(String kbId, String query, int topK);

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

    class SearchTrace {
        public final List<StageResult> bm25Results;
        public final List<StageResult> vectorResults;
        public final List<StageResult> rrfResults;
        public final List<StageResult> finalResults;

        public SearchTrace(List<StageResult> bm25Results,
                           List<StageResult> vectorResults,
                           List<StageResult> rrfResults,
                           List<StageResult> finalResults) {
            this.bm25Results = bm25Results;
            this.vectorResults = vectorResults;
            this.rrfResults = rrfResults;
            this.finalResults = finalResults;
        }
    }

    class StageResult {
        public final int rank;
        public final String chunkId;
        public final String content;
        public final String metadata;
        public final double score;
        public final String reason;

        public StageResult(int rank, String chunkId, String content, String metadata, double score, String reason) {
            this.rank = rank;
            this.chunkId = chunkId;
            this.content = content;
            this.metadata = metadata;
            this.score = score;
            this.reason = reason;
        }
    }
}
