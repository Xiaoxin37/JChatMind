package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.ChunkBgeM3IndexService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Lucene-based BM25 full-text index service.
 * Index stored on local filesystem at configured path.
 */
@Service
@Slf4j
public class ChunkBgeM3IndexServiceImpl implements ChunkBgeM3IndexService {

    private final Analyzer analyzer = new SmartChineseAnalyzer();
    private IndexWriter indexWriter;
    private Directory directory;

    @Value("${rag.retrieval.bm25-index-path:./data/bm25-index}")
    private String indexPath;

    @PostConstruct
    public void initializeIndex() {
        try {
            Path path = Paths.get(indexPath);
            Files.createDirectories(path);
            directory = FSDirectory.open(path);

            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setSimilarity(new BM25Similarity());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            indexWriter = new IndexWriter(directory, config);
            log.info("BM25 索引初始化完成: {}", indexPath);
        } catch (IOException e) {
            log.error("BM25 索引初始化失败", e);
            throw new RuntimeException("BM25 索引初始化失败: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void closeIndex() {
        try {
            if (indexWriter != null) {
                indexWriter.close();
            }
            if (directory != null) {
                directory.close();
            }
            log.info("BM25 索引已关闭");
        } catch (IOException e) {
            log.warn("关闭 BM25 索引时出错", e);
        }
    }

    @Override
    public void indexChunk(String chunkId, String kbId, String docId, String content) {
        indexChunks(List.of(new ChunkIndexRecord(chunkId, kbId, docId, content)));
    }

    @Override
    public void indexChunks(List<ChunkIndexRecord> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            for (ChunkIndexRecord chunk : chunks) {
                Document doc = new Document();
                doc.add(new StringField("chunkId", chunk.chunkId, Field.Store.YES));
                doc.add(new StringField("kbId", chunk.kbId, Field.Store.YES));
                doc.add(new StringField("docId", chunk.docId, Field.Store.YES));
                doc.add(new TextField("content", chunk.content, Field.Store.YES));
                indexWriter.addDocument(doc);
            }
            indexWriter.commit();
        } catch (IOException e) {
            log.error("批量索引 chunks 失败: count={}", chunks.size(), e);
            throw new RuntimeException("批量索引 chunks 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteByDocId(String docId) {
        try {
            long deleted = indexWriter.deleteDocuments(new Term("docId", docId));
            indexWriter.commit();
            log.info("BM25 索引删除: docId={}, 删除了 {} 条记录", docId, deleted);
        } catch (IOException e) {
            log.error("删除 BM25 索引失败: docId={}", docId, e);
            throw new RuntimeException("删除 BM25 索引失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Bm25Result> search(String kbId, String query, int topK) {
        try (IndexReader reader = DirectoryReader.open(indexWriter)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());

            QueryParser parser = new QueryParser("content", analyzer);
            Query contentQuery = parser.parse(query);
            Query luceneQuery = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term("kbId", kbId)), BooleanClause.Occur.FILTER)
                    .add(contentQuery, BooleanClause.Occur.MUST)
                    .build();

            TopDocs topDocs = searcher.search(luceneQuery, topK);
            List<Bm25Result> results = new ArrayList<>();

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                results.add(new Bm25Result(doc.get("chunkId"), scoreDoc.score));
            }

            return results;
        } catch (Exception e) {
            log.error("BM25 搜索失败: query={}", query, e);
            throw new RuntimeException("BM25 搜索失败: " + e.getMessage(), e);
        }
    }
}
