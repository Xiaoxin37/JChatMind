package com.kama.jchatmind.service;

import java.util.List;

/**
 * Lucene BM25 full-text index service for chunks.
 */
public interface ChunkBgeM3IndexService {
    /**
     * Index a chunk's content for BM25 search.
     */
    void indexChunk(String chunkId, String docId, String content);

    /**
     * Delete all index entries for a document.
     */
    void deleteByDocId(String docId);

    /**
     * Search the BM25 index.
     *
     * @param query search query
     * @param topK  number of results
     * @return scored results ranked by BM25 relevance
     */
    List<Bm25Result> search(String query, int topK);

    /**
     * BM25 search result with chunk ID and score.
     */
    class Bm25Result {
        public final String chunkId;
        public final double score;

        public Bm25Result(String chunkId, double score) {
            this.chunkId = chunkId;
            this.score = score;
        }
    }
}
