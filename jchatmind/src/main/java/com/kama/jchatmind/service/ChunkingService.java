package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ParsedDocument;

import java.util.List;

/**
 * Semantic chunking service.
 * Splits ParsedDocument content into chunks by semantic boundaries.
 */
public interface ChunkingService {
    /**
     * Split a ParsedDocument into multiple chunks.
     *
     * @param doc       Source document
     * @param maxTokens Maximum tokens per chunk (default 512)
     * @return List of chunk content strings
     */
    List<String> chunk(ParsedDocument doc, int maxTokens);
}
