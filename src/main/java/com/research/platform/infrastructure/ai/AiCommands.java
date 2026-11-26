package com.research.platform.infrastructure.ai;

import java.util.List;

/**
 * 调用 FastAPI 的请求参数
 *
 * <p>大文件只传文件相对路径，避免服务间重复传输。</p>
 */
public final class AiCommands {

    private AiCommands() {
    }

    /**
     * PDF 解析请求（POST /internal/v1/documents/parse）
     */
    public record Parse(
            Long documentId,
            Long parseRecordId,
            Long taskId,
            String filePath,
            String fileName
    ) {
    }

    /**
     * 文献分析请求（POST /internal/v1/documents/analyze）
     */
    public record Analyze(
            Long documentId,
            Long taskId,
            String analysisType,
            String title,
            String abstractText,
            String markdownPath
    ) {
    }

    /**
     * RAG 问答请求（POST /internal/v1/rag/answer）
     */
    public record Ask(
            Long userId,
            String question,
            List<Long> documentIds,
            List<Long> parseRecordIds,
            int topK
    ) {
    }

    /**
     * 语义检索请求（POST /internal/v1/search/semantic）
     */
    public record SemanticSearch(
            Long userId,
            String query,
            List<Long> documentIds,
            List<Long> parseRecordIds,
            int topK
    ) {
    }

    /**
     * 文本块向量化请求（POST /internal/v1/embeddings/index）
     */
    public record IndexChunk(
            String chunkId,
            Long userId,
            Long documentId,
            Long parseRecordId,
            String section,
            Integer pageStart,
            Integer pageEnd,
            String content
    ) {
    }

    /**
     * 批量向量化请求
     */
    public record Index(List<IndexChunk> chunks) {
    }

    /**
     * 文献元数据识别请求（POST /internal/v1/documents/metadata）
     */
    public record Metadata(
            Long documentId,
            Long parseRecordId,
            String filePath,
            String fileName,
            String markdown,
            String title
    ) {
    }

    /**
     * 清理某文献的旧版本向量（POST /internal/v1/embeddings/cleanup）
     */
    public record Cleanup(Long documentId, Long keepParseRecordId) {
    }

    /**
     * 知识实体/关系抽取请求（POST /internal/v1/knowledge/extract）
     */
    public record Knowledge(
            Long documentId,
            Long parseRecordId,
            String markdownPath,
            String title
    ) {
    }
}
