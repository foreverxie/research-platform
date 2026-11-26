package com.research.platform.infrastructure.ai;

import java.util.List;

/**
 * FastAPI 返回结果
 */
public final class AiResults {

    private AiResults() {
    }

    /**
     * 解析出的文本块
     */
    public record Chunk(
            String section,
            Integer pageStart,
            Integer pageEnd,
            String content,
            Integer tokenCount
    ) {
    }

    /**
     * PDF 解析结果
     */
    public record Parse(
            String markdown,
            String json,
            Integer pageCount,
            String parserName,
            String parserVersion,
            List<Chunk> chunks
    ) {
    }

    /**
     * 文献分析结果
     */
    public record Analysis(
            String summary,
            String researchBackground,
            String researchMethod,
            List<String> innovationPoints,
            String experimentalResults,
            String conclusion,
            List<String> keywords,
            String modelName,
            Integer promptTokens,
            Integer completionTokens
    ) {
    }

    /**
     * 问答引用
     */
    public record Reference(
            Long documentId,
            String chunkId,
            Integer pageNumber,
            String section,
            Double relevanceScore,
            String snippet
    ) {
    }

    /**
     * RAG 回答
     */
    public record Answer(
            String answer,
            String modelName,
            Integer promptTokens,
            Integer completionTokens,
            List<Reference> references
    ) {
    }

    /**
     * 语义检索命中
     */
    public record Hit(
            Long documentId,
            String chunkId,
            String section,
            Integer pageStart,
            Integer pageEnd,
            String content,
            Double score
    ) {
    }

    /**
     * 语义检索结果
     */
    public record Search(List<Hit> hits) {
    }

    /**
     * 向量化结果
     */
    public record Index(Integer indexed, Integer failed, String message) {
    }

    /**
     * 文献元数据识别结果
     */
    public record Metadata(
            String title,
            List<String> authors,
            String journal,
            String doi,
            Integer publicationYear,
            String abstractText,
            List<String> keywords,
            String documentType,
            String language,
            String modelName
    ) {
    }

    /**
     * 旧版本向量清理结果
     */
    public record Cleanup(Integer deleted, String message) {
    }

    /**
     * AI 服务健康检查结果
     */
    public record Health(
            String status,
            String version,
            String parser,
            String embedding,
            String llm,
            String vectorStore
    ) {
    }

    /**
     * 知识实体
     */
    public record KnowledgeEntity(
            String name,
            String type,
            String description,
            Double confidence,
            String evidence
    ) {
    }

    /**
     * 知识关系
     */
    public record KnowledgeRelation(
            String sourceName,
            String sourceType,
            String targetName,
            String targetType,
            String relationType,
            Double confidence,
            String evidence
    ) {
    }

    /**
     * 知识抽取结果
     */
    public record Knowledge(
            List<KnowledgeEntity> entities,
            List<KnowledgeRelation> relations,
            String modelName
    ) {
    }
}
