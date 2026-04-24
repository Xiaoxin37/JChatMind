package com.kama.jchatmind.service;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    List<float[]> embedBatch(List<String> texts);

    List<String> similaritySearch(String kbId, String title);

    List<String> similaritySearch(String kbId, String query, int topK);

    /**
     * Rerank documents by relevance to the query using cosine similarity.
     *
     * @param query     the search query
     * @param documents list of document contents to rerank
     * @return reranked results sorted by relevance score descending
     */
    List<RerankResult> rerank(String query, List<String> documents);

    /**
     * Rerank result with original index and relevance score.
     */
    class RerankResult {
        public final int originalIndex;
        public final double score;

        public RerankResult(int originalIndex, double score) {
            this.originalIndex = originalIndex;
            this.score = score;
        }
    }
}
