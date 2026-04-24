package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.model.vo.DocumentVO;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.ChunkBgeM3IndexService;
import com.kama.jchatmind.service.ChunkingService;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.model.dto.ParsedDocument;
import com.kama.jchatmind.service.DocumentParserService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
@AllArgsConstructor
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {

    private static final int INGESTION_BATCH_SIZE = 16;

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final DocumentParserService documentParserService;
    private final ChunkingService chunkingService;
    private final ChunkBgeM3IndexService chunkBgeM3IndexService;
    private final ObjectMapper objectMapper;
    private final MarkdownParserService markdownParserService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final Executor taskExecutor;

    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAll();
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            // 将 CreateDocumentRequest 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(request);

            // 将 DocumentDTO 转换为 Document 实体
            Document document = documentConverter.toEntity(documentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            // 返回生成的 documentId
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();
            if (!isSupportedFormat(filetype)) {
                throw new BizException("不支持的文件格式: " + filetype);
            }

            // 创建文档记录（先创建记录，获取 documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的 documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件
            String filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录，保存文件路径到 metadata
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            metadata.setProcessingStatus("PROCESSING");
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            documentMapper.updateById(updatedDocument);

            log.info("文档上传成功: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);

            scheduleDocumentProcessing(kbId, documentId, filePath, filetype, originalFilename);

            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .build();
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BizException("文件保存失败: " + e.getMessage());
        }
    }

    private void scheduleDocumentProcessing(String kbId, String documentId, String filePath, String filetype, String filename) {
        Runnable task = () -> {
            try {
                processDocument(kbId, documentId, filePath, filetype, filename);
            } catch (Exception e) {
                log.warn("后台文档处理任务结束于失败状态: documentId={}, error={}", documentId, e.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskExecutor.execute(task);
                }
            });
        } else {
            taskExecutor.execute(task);
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }

        // 删除文件
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                String filePath = documentDTO.getMetadata().getFilePath();
                documentStorageService.deleteFile(filePath);
            }
        } catch (Exception e) {
            log.warn("删除文件失败，继续删除文档记录: documentId={}, error={}", documentId, e.getMessage());
            // 即使文件删除失败，也继续删除数据库记录
        }

        // 删除数据库记录
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("删除文档失败");
        }

        // 同步删除 BM25 索引
        try {
            chunkBgeM3IndexService.deleteByDocId(documentId);
        } catch (Exception e) {
            log.warn("删除 BM25 索引失败: documentId={}, error={}", documentId, e.getMessage());
        }
    }

    /**
     * 解析并处理文档，生成 chunks（支持多格式）
     */
    private void processDocument(String kbId, String documentId, String filePath, String filetype, String filename) {
        try {
            log.info("开始处理文档: kbId={}, documentId={}, filePath={}, filetype={}", kbId, documentId, filePath, filetype);

            Path path = documentStorageService.getFilePath(filePath);
            try (InputStream inputStream = Files.newInputStream(path)) {
                List<ParsedDocument> sections = documentParserService.parse(inputStream, filetype, filename);

                if (sections.isEmpty()) {
                    log.warn("文档解析后没有找到任何章节: documentId={}", documentId);
                    return;
                }

                LocalDateTime now = LocalDateTime.now();
                int chunkCount = 0;
                List<ChunkBgeM3> pendingChunks = new ArrayList<>(INGESTION_BATCH_SIZE);

                for (ParsedDocument section : sections) {
                    String title = section.getTitle();

                    if (title == null || title.trim().isEmpty()) {
                        continue;
                    }

                    // Split into chunks using semantic chunking service
                    List<String> chunkContents = chunkingService.chunk(section, 0);
                    if (chunkContents.isEmpty()) {
                        continue;
                    }

                    // Build hierarchy metadata JSON
                    Map<String, Object> metaBase = new java.util.LinkedHashMap<>();
                    metaBase.put("hierarchy", section.getHierarchy());
                    metaBase.put("sourceFormat", section.getSourceFormat());
                    metaBase.put("title", title);

                    for (int i = 0; i < chunkContents.size(); i++) {
                        String chunkContent = chunkContents.get(i);
                        metaBase.put("chunkIndex", i);
                        metaBase.put("totalChunks", chunkContents.size());
                        String metadataJson = objectMapper.writeValueAsString(metaBase);

                        ChunkBgeM3 chunk = ChunkBgeM3.builder()
                                .kbId(kbId)
                                .docId(documentId)
                                .content(chunkContent)
                                .metadata(metadataJson)
                                .createdAt(now)
                                .updatedAt(now)
                                .build();

                        pendingChunks.add(chunk);
                        if (pendingChunks.size() >= INGESTION_BATCH_SIZE) {
                            chunkCount += flushChunkBatch(pendingChunks);
                        }
                    }
                }
                chunkCount += flushChunkBatch(pendingChunks);
                log.info("文档处理完成: documentId={}, filetype={}, 共生成 {} 个 chunks", documentId, filetype, chunkCount);
                updateProcessingStatus(documentId, "READY", null);
            }
        } catch (Exception e) {
            log.error("处理文档失败: documentId={}, filetype={}", documentId, filetype, e);
            updateProcessingStatus(documentId, "FAILED", e.getMessage());
            cleanupFailedDocumentProcessing(documentId);
            throw new BizException("文档解析失败: " + e.getMessage());
        }
    }

    private int flushChunkBatch(List<ChunkBgeM3> pendingChunks) {
        if (pendingChunks.isEmpty()) {
            return 0;
        }

        List<ChunkBgeM3> batch = new ArrayList<>(pendingChunks);
        pendingChunks.clear();

        long start = System.currentTimeMillis();
        List<String> contents = batch.stream()
                .map(ChunkBgeM3::getContent)
                .toList();
        List<float[]> embeddings = ragService.embedBatch(contents);
        if (embeddings.size() != batch.size()) {
            throw new BizException("批量向量化结果数量不匹配");
        }

        List<ChunkBgeM3IndexService.ChunkIndexRecord> indexRecords = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            ChunkBgeM3 chunk = batch.get(i);
            if (chunk.getId() == null || chunk.getId().isBlank()) {
                chunk.setId(UUID.randomUUID().toString());
            }
            chunk.setEmbedding(embeddings.get(i));
            indexRecords.add(new ChunkBgeM3IndexService.ChunkIndexRecord(
                    chunk.getId(),
                    chunk.getKbId(),
                    chunk.getDocId(),
                    chunk.getContent()
            ));
        }

        int inserted = chunkBgeM3Mapper.insertBatch(batch);
        if (inserted != batch.size()) {
            throw new BizException("批量创建 chunk 数量不匹配: expected=" + batch.size() + ", actual=" + inserted);
        }

        chunkBgeM3IndexService.indexChunks(indexRecords);
        log.info("批量处理 chunks 完成: count={}, elapsedMs={}", inserted, System.currentTimeMillis() - start);
        return inserted;
    }

    private void updateProcessingStatus(String documentId, String status, String errorMessage) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            return;
        }
        try {
            DocumentDTO dto = documentConverter.toDTO(document);
            DocumentDTO.MetaData metadata = dto.getMetadata();
            if (metadata == null) {
                metadata = new DocumentDTO.MetaData();
            }
            metadata.setProcessingStatus(status);
            metadata.setProcessingError(errorMessage);
            dto.setMetadata(metadata);
            dto.setUpdatedAt(LocalDateTime.now());

            Document updatedDocument = documentConverter.toEntity(dto);
            updatedDocument.setId(documentId);
            updatedDocument.setKbId(document.getKbId());
            updatedDocument.setCreatedAt(document.getCreatedAt());
            updatedDocument.setUpdatedAt(dto.getUpdatedAt());
            documentMapper.updateById(updatedDocument);
        } catch (Exception ex) {
            log.warn("更新文档处理状态失败: documentId={}, status={}", documentId, status, ex);
        }
    }

    private void cleanupFailedDocumentProcessing(String documentId) {
        try {
            chunkBgeM3Mapper.deleteByDocId(documentId);
        } catch (Exception cleanupError) {
            log.warn("清理失败文档 chunks 失败: documentId={}, error={}", documentId, cleanupError.getMessage());
        }
        try {
            chunkBgeM3IndexService.deleteByDocId(documentId);
        } catch (Exception cleanupError) {
            log.warn("清理失败文档 BM25 索引失败: documentId={}, error={}", documentId, cleanupError.getMessage());
        }
    }

    /**
     * 检查文件格式是否支持
     */
    private boolean isSupportedFormat(String filetype) {
        String lower = filetype.toLowerCase();
        return Set.of("md", "markdown", "txt", "docx", "pdf", "xlsx", "xls", "csv").contains(lower);
    }

    /**
     * 处理 Markdown 文档（保留兼容，不再主流程调用）
     */
    private void processMarkdownDocument(String kbId, String documentId, String filePath) {
        try {
            log.info("开始处理 Markdown 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);

            Path path = documentStorageService.getFilePath(filePath);
            try (InputStream inputStream = Files.newInputStream(path)) {
                List<ParsedDocument> sections = markdownParserService.parseMarkdown(inputStream);

                if (sections.isEmpty()) {
                    log.warn("Markdown 文档解析后没有找到任何章节: documentId={}", documentId);
                    return;
                }

                LocalDateTime now = LocalDateTime.now();
                int chunkCount = 0;

                for (ParsedDocument section : sections) {
                    String title = section.getTitle();
                    String content = section.getContent();

                    if (title == null || title.trim().isEmpty()) {
                        continue;
                    }

                    float[] embedding = ragService.embed(title);

                    ChunkBgeM3 chunk = ChunkBgeM3.builder()
                            .kbId(kbId)
                            .docId(documentId)
                            .content(content != null ? content : "")
                            .metadata(null)
                            .embedding(embedding)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();

                    int result = chunkBgeM3Mapper.insert(chunk);

                    if (result > 0) {
                        chunkCount++;
                        log.debug("创建 chunk 成功: title={}, chunkId={}", title, chunk.getId());
                    } else {
                        log.warn("创建 chunk 失败: title={}", title);
                    }
                }
                log.info("Markdown 文档处理完成: documentId={}, 共生成 {} 个 chunks", documentId, chunkCount);
            }
        } catch (Exception e) {
            log.error("处理 Markdown 文档失败: documentId={}", documentId, e);
        }
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            // 查询现有的文档
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("文档不存在: " + documentId);
            }

            // 将现有 Document 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);

            // 使用 UpdateDocumentRequest 更新 DocumentDTO
            documentConverter.updateDTOFromRequest(documentDTO, request);

            // 将更新后的 DocumentDTO 转换回 Document 实体
            Document updatedDocument = documentConverter.toEntity(documentDTO);

            // 保留原有的 ID、kbId 和创建时间
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw new BizException("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新文档时发生序列化错误: " + e.getMessage());
        }
    }
}
