package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.ParsedDocument;
import com.kama.jchatmind.service.ChunkingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Semantic chunking implementation.
 * Detects document-local section boundaries first, then splits by paragraph,
 * and falls back to sentence splitting for long paragraphs.
 * Applies configurable overlap between consecutive chunks.
 */
@Service
@Slf4j
public class ChunkingServiceImpl implements ChunkingService {

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

        List<String> documentSections = splitByDocumentSections(content);
        if (documentSections.size() > 1) {
            List<String> chunks = new ArrayList<>();
            for (String section : documentSections) {
                chunks.addAll(chunkPlainText(section, limit));
            }
            log.info("文档按内容结构切分完成: {} -> {} 个 chunks (maxTokens={}, overlap={})",
                    doc.getTitle(), chunks.size(), limit, overlapRatio);
            return chunks;
        }

        List<String> segments = chunkPlainText(content, limit);
        log.info("文档切分完成: {} -> {} 个 chunks (maxTokens={}, overlap={})",
                doc.getTitle(), segments.size(), limit, overlapRatio);
        return segments;
    }

    private List<String> chunkPlainText(String content, int limit) {
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
            segments = applyOverlap(segments, limit);
        }

        return segments;
    }

    private List<String> splitByDocumentSections(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        String[] lines = normalized.split("\n");
        if (lines.length < 3) {
            return List.of();
        }

        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            String previous = i > 0 ? lines[i - 1].trim() : "";
            String next = i + 1 < lines.length ? lines[i + 1].trim() : "";
            if (isLikelySectionHeading(line, previous, next)) {
                starts.add(i);
            }
        }

        if (starts.size() < 2) {
            return List.of();
        }

        List<String> sections = new ArrayList<>();
        String intro = joinLines(lines, 0, starts.get(0)).trim();
        if (!intro.isEmpty()) {
            sections.add(intro);
        }

        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : lines.length;
            String section = joinLines(lines, start, end).trim();
            if (!section.isEmpty()) {
                sections.add(section);
            }
        }
        return sections;
    }

    private boolean isLikelySectionHeading(String line, String previous, String next) {
        if (line.isEmpty() || next.isEmpty()) {
            return false;
        }

        String normalized = line.replaceAll("\\s+", "");
        if (normalized.length() > 80 || estimateTokens(line) > 32) {
            return false;
        }
        if (endsLikeSentence(line)) {
            return false;
        }

        boolean hasText = line.codePoints().anyMatch(Character::isLetter);
        if (!hasText) {
            return false;
        }

        if (line.startsWith("#")) {
            return true;
        }
        if (line.matches("^(第[一二三四五六七八九十百千万0-9]+[章节篇部分]).*")) {
            return true;
        }
        if (line.matches("^\\d+(\\.\\d+)*[、.．)]\\s*\\S+.*")) {
            return true;
        }
        if (line.endsWith(":") || line.endsWith("：")) {
            return true;
        }

        // A short standalone line followed by content is usually a local section
        // heading, regardless of the document domain.
        boolean previousBoundary = previous.isEmpty() || previous.endsWith(":") || previous.endsWith("：");
        boolean nextLooksLikeBody = estimateTokens(next) >= 4 || endsLikeSentence(next);
        return previousBoundary && nextLooksLikeBody && estimateTokens(line) <= 12;
    }

    private String joinLines(String[] lines, int startInclusive, int endExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = startInclusive; i < endExclusive; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                if (sb.length() > 0 && !sb.toString().endsWith("\n\n")) {
                    sb.append("\n\n");
                }
                continue;
            }
            if (sb.length() > 0 && !sb.toString().endsWith("\n")) {
                sb.append("\n");
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private boolean endsLikeSentence(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        char last = text.trim().charAt(text.trim().length() - 1);
        return isSentenceBoundary(last) || last == ';' || last == '；';
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
        List<String> sentences = extractSentences(text);

        // Accumulate sentences into chunks
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String sentence : sentences) {
            int sentTokens = estimateTokens(sentence);

            if (sentTokens > limit) {
                if (currentTokens > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                    currentTokens = 0;
                }
                chunks.addAll(splitOversizedText(sentence, limit));
                continue;
            }

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

        return chunks.isEmpty() ? splitOversizedText(text, limit) : chunks;
    }

    /**
     * Apply overlap between consecutive chunks.
     * The last ~overlapRatio% of each chunk becomes the prefix of the next chunk.
     */
    private List<String> applyOverlap(List<String> chunks, int limit) {
        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String prevChunk = chunks.get(i - 1);
            String currentChunk = chunks.get(i);

            int currentTokens = estimateTokens(currentChunk);
            int overlapBudget = Math.min((int) (estimateTokens(prevChunk) * overlapRatio), limit - currentTokens);
            if (overlapBudget <= 0) {
                result.add(currentChunk);
                continue;
            }

            String overlapText = extractOverlapText(prevChunk, overlapBudget);

            if (!overlapText.isEmpty()) {
                String merged = overlapText + "\n\n" + currentChunk;
                if (estimateTokens(merged) <= limit) {
                    result.add(merged);
                } else {
                    result.add(currentChunk);
                }
            } else {
                result.add(currentChunk);
            }
        }

        return result;
    }

    private List<String> extractSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (isSentenceBoundary(c)) {
                addIfNotBlank(sentences, current.toString());
                current = new StringBuilder();
            }
        }

        if (current.length() > 0) {
            addIfNotBlank(sentences, current.toString());
        }

        return sentences.isEmpty() ? List.of(text.trim()) : sentences;
    }

    private List<String> splitOversizedText(String text, int limit) {
        List<String> chunks = new ArrayList<>();
        String remaining = text.trim();

        while (!remaining.isEmpty()) {
            if (estimateTokens(remaining) <= limit) {
                chunks.add(remaining);
                break;
            }

            int splitIndex = findMaxFittingIndex(remaining, limit);
            if (splitIndex <= 0 || splitIndex >= remaining.length()) {
                splitIndex = Math.min(remaining.length(), Math.max(1, limit * 2));
            }

            String chunk = remaining.substring(0, splitIndex).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            remaining = remaining.substring(splitIndex).trim();
        }

        return chunks;
    }

    private int findMaxFittingIndex(String text, int limit) {
        int bestBoundary = -1;
        int bestAny = -1;

        for (int i = 1; i <= text.length(); i++) {
            String candidate = text.substring(0, i).trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (estimateTokens(candidate) > limit) {
                break;
            }
            bestAny = i;
            char c = text.charAt(i - 1);
            if (Character.isWhitespace(c) || isSentenceBoundary(c) || c == ',' || c == '，' || c == ';' || c == '；') {
                bestBoundary = i;
            }
        }

        return bestBoundary > 0 ? bestBoundary : bestAny;
    }

    private String extractOverlapText(String prevChunk, int overlapBudget) {
        String trimmedPrev = prevChunk.trim();
        if (trimmedPrev.isEmpty()) {
            return "";
        }

        int start = trimmedPrev.length();
        for (int i = trimmedPrev.length() - 1; i >= 0; i--) {
            String candidate = trimmedPrev.substring(i).trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (estimateTokens(candidate) > overlapBudget) {
                break;
            }
            start = i;
        }

        if (start >= trimmedPrev.length()) {
            return "";
        }

        String candidate = trimmedPrev.substring(start).trim();
        int breakPoint = findBreakPoint(candidate);
        if (breakPoint > 0 && breakPoint < candidate.length()) {
            String aligned = candidate.substring(breakPoint).trim();
            if (!aligned.isEmpty() && estimateTokens(aligned) <= overlapBudget) {
                candidate = aligned;
            }
        }
        return candidate;
    }

    private void addIfNotBlank(List<String> sentences, String text) {
        String trimmed = text.trim();
        if (!trimmed.isEmpty()) {
            sentences.add(trimmed);
        }
    }

    private boolean isSentenceBoundary(char c) {
        return c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?';
    }

    /**
     * Find a good break point in text (sentence or word boundary).
     */
    private int findBreakPoint(String text) {
        // Try to find sentence boundary
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isSentenceBoundary(c)) {
                return i + 1;
            }
        }
        // Fallback: space boundary
        int spaceIdx = text.indexOf(' ');
        if (spaceIdx > 0) {
            return spaceIdx + 1;
        }
        int newlineIdx = text.indexOf('\n');
        if (newlineIdx > 0) {
            return newlineIdx + 1;
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
