package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.ParsedDocument;
import com.kama.jchatmind.service.ChunkingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Semantic chunking implementation.
 * Splits by paragraph first, falls back to sentence splitting for long paragraphs.
 * Applies configurable overlap between consecutive chunks.
 */
@Service
@Slf4j
public class ChunkingServiceImpl implements ChunkingService {

    // Sentence boundary pattern: Chinese and English sentence terminators
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[。！？.!?]+");

    @Value("${rag.chunking.max-tokens:512}")
    private int maxTokens;

    @Value("${rag.chunking.overlap-ratio:0.15}")
    private double overlapRatio;

    @Override
    public List<String> chunk(ParsedDocument doc, int maxTok) {
        String content = doc.getContent();
        if (content == null || content.trim().isEmpty()) {
            return List.of();
        }

        int limit = maxTok > 0 ? maxTok : this.maxTokens;

        // Step 1: Split by paragraph
        String[] paragraphs = content.split("\\n\\n+");

        // Check if all paragraphs fit within limit
        boolean allFit = true;
        for (String p : paragraphs) {
            if (estimateTokens(p) > limit) {
                allFit = false;
                break;
            }
        }

        List<String> segments;
        if (allFit) {
            // All paragraphs fit — accumulate into chunks
            segments = accumulateByParagraph(paragraphs, limit);
        } else {
            // Some paragraphs exceed limit — need sentence-level splitting
            segments = accumulateWithFallback(paragraphs, limit);
        }

        // Apply overlap if more than one chunk
        if (segments.size() > 1 && overlapRatio > 0) {
            segments = applyOverlap(segments);
        }

        log.info("文档切分完成: {} -> {} 个 chunks (maxTokens={}, overlap={})",
                doc.getTitle(), segments.size(), limit, overlapRatio);
        return segments;
    }

    /**
     * Accumulate paragraphs into chunks without any needing sentence splitting.
     */
    private List<String> accumulateByParagraph(String[] paragraphs, int limit) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            int paraTokens = estimateTokens(trimmed);

            if (currentTokens + paraTokens > limit && currentTokens > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
                currentTokens = 0;
            }

            if (currentTokens > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
            currentTokens += paraTokens;
        }

        if (currentTokens > 0) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    /**
     * Split paragraphs that exceed limit by sentence boundary, others by paragraph.
     */
    private List<String> accumulateWithFallback(String[] paragraphs, int limit) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            int paraTokens = estimateTokens(trimmed);

            if (paraTokens > limit) {
                // Flush current buffer first
                if (currentTokens > 0) {
                    segments.add(current.toString().trim());
                    current = new StringBuilder();
                    currentTokens = 0;
                }

                // Split long paragraph by sentences
                List<String> sentenceChunks = splitBySentence(trimmed, limit);
                segments.addAll(sentenceChunks);
            } else {
                // Normal paragraph accumulation
                if (currentTokens + paraTokens > limit && currentTokens > 0) {
                    segments.add(current.toString().trim());
                    current = new StringBuilder();
                    currentTokens = 0;
                }

                if (currentTokens > 0) {
                    current.append("\n\n");
                }
                current.append(trimmed);
                currentTokens += paraTokens;
            }
        }

        if (currentTokens > 0) {
            segments.add(current.toString().trim());
        }

        return segments;
    }

    /**
     * Split a single long paragraph by sentence boundary.
     */
    private List<String> splitBySentence(String text, int limit) {
        List<String> chunks = new ArrayList<>();
        // Split keeping the delimiter
        String[] parts = SENTENCE_BOUNDARY.split(text, -1);
        String[] delimiters = SENTENCE_BOUNDARY.matcher(text).replaceAll("\u0000").split("\u0000", -1);

        // Rebuild sentences with their terminators
        List<String> sentences = new ArrayList<>();
        int di = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;
            // Find the delimiter that was after this part
            String delimiter = "";
            int nextNull = text.indexOf('\u0000', di);
            if (nextNull >= 0) {
                delimiter = text.substring(di, nextNull);
                di = nextNull + 1;
            }
            sentences.add(part + delimiter);
        }

        // Accumulate sentences into chunks
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String sentence : sentences) {
            int sentTokens = estimateTokens(sentence);

            if (currentTokens + sentTokens > limit && currentTokens > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
                currentTokens = 0;
            }

            current.append(sentence);
            currentTokens += sentTokens;
        }

        if (currentTokens > 0) {
            chunks.add(current.toString().trim());
        }

        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    /**
     * Apply overlap between consecutive chunks.
     * The last ~overlapRatio% of each chunk becomes the prefix of the next chunk.
     */
    private List<String> applyOverlap(List<String> chunks) {
        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String prevChunk = chunks.get(i - 1);
            String currentChunk = chunks.get(i);

            int overlapTokens = (int) (estimateTokens(prevChunk) * overlapRatio);
            if (overlapTokens <= 0) {
                result.add(currentChunk);
                continue;
            }

            // Extract last N chars that approximate overlapTokens
            int overlapChars = overlapTokens * 2; // Conservative: 2 chars/token
            overlapChars = Math.min(overlapChars, prevChunk.length());

            String overlapText = prevChunk.substring(prevChunk.length() - overlapChars).trim();

            // Find a good break point (end of sentence or word boundary)
            int breakPoint = findBreakPoint(overlapText);
            if (breakPoint > 0) {
                overlapText = overlapText.substring(breakPoint).trim();
            }

            if (!overlapText.isEmpty()) {
                result.add(overlapText + "\n\n" + currentChunk);
            } else {
                result.add(currentChunk);
            }
        }

        return result;
    }

    /**
     * Find a good break point in text (sentence or word boundary).
     */
    private int findBreakPoint(String text) {
        // Try to find sentence boundary
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return i + 1;
            }
        }
        // Fallback: space boundary
        int spaceIdx = text.indexOf(' ');
        if (spaceIdx > 0) {
            return spaceIdx + 1;
        }
        return 0;
    }

    /**
     * Estimate token count for text.
     * Chinese/Unicode: ~2 chars per token
     * ASCII English: ~4 chars per token
     */
    int estimateTokens(String text) {
        int chineseChars = 0;
        int englishChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 127) {
                chineseChars++;
            } else {
                englishChars++;
            }
        }
        return Math.max(1, chineseChars / 2 + englishChars / 4);
    }
}
