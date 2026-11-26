package com.research.platform.infrastructure.ai;

/**
 * AI 服务客户端
 *
 * <p>业务层只依赖该接口，具体模型、解析工具和向量库都封装在 FastAPI 内部，
 * 便于后续更换模型服务（NFR-002）。</p>
 */
public interface AiServiceClient {

    /**
     * 提交 PDF 解析
     */
    AiResults.Parse parse(AiCommands.Parse command);

    /**
     * 文献 AI 分析
     */
    AiResults.Analysis analyze(AiCommands.Analyze command);

    /**
     * RAG 科研问答
     */
    AiResults.Answer ask(AiCommands.Ask command);

    /**
     * 语义检索
     */
    AiResults.Search semanticSearch(AiCommands.SemanticSearch command);

    /**
     * 文本块批量向量化（解析成功后调用）
     */
    AiResults.Index indexChunks(AiCommands.Index command);

    /**
     * 从 PDF 与首页正文识别文献元数据
     */
    AiResults.Metadata extractMetadata(AiCommands.Metadata command);

    /**
     * 清理某文献除当前版本外的旧向量
     */
    AiResults.Cleanup cleanupVectors(AiCommands.Cleanup command);

    /**
     * 读取 AI 服务实际生效的解析器/向量库/模型状态
     */
    AiResults.Health health();

    /**
     * 从论文原文抽取知识实体与关系
     */
    AiResults.Knowledge extractKnowledge(AiCommands.Knowledge command);

    /**
     * 探活，用于系统状态页
     */
    boolean ping();
}
