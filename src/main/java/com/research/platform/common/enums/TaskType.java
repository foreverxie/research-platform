package com.research.platform.common.enums;

/**
 * AI 任务类型（对应 ai_task.task_type）
 */
public enum TaskType {

    /** PDF 解析（MinerU） */
    PDF_PARSE,

    /** 文本分块 */
    TEXT_CHUNK,

    /** 文本向量化 */
    EMBEDDING,

    /** 文献 AI 分析 */
    AI_ANALYSIS,

    /** 科研问答 */
    RAG_QUESTION,

    /** 文献推荐 */
    PAPER_RECOMMEND,

    /** 文献对比 */
    PAPER_COMPARE,

    /** 知识实体与关系抽取 */
    KNOWLEDGE_EXTRACT
}
