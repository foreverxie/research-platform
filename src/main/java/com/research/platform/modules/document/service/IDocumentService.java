package com.research.platform.modules.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.research.platform.common.result.PageResult;
import com.research.platform.common.vo.RecommendationItemVO;
import com.research.platform.modules.document.dto.AnalyzeRequestDTO;
import com.research.platform.modules.document.dto.DocumentQueryDTO;
import com.research.platform.modules.document.dto.DocumentUpdateDTO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.vo.DocumentContentVO;
import com.research.platform.modules.document.vo.DocumentDetailVO;
import com.research.platform.modules.document.vo.DocumentVO;
import com.research.platform.modules.document.vo.ReparseResultVO;
import com.research.platform.modules.document.vo.UploadResultVO;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文献服务
 *
 * <p>所有方法内部都从 {@code CurrentUser} 获取当前用户，并经过
 * {@link DocumentAccessService} 的归属校验，禁止仅凭文献ID访问他人数据。</p>
 */
public interface IDocumentService extends IService<Document> {

    /**
     * 文献列表（分页 + 筛选 + 排序）
     */
    PageResult<DocumentVO> listDocuments(DocumentQueryDTO query);

    /**
     * 上传文献：保存 PDF、创建解析任务与解析记录
     */
    UploadResultVO upload(MultipartFile file,
                          String title,
                          String authors,
                          String keywords,
                          String journal,
                          String doi,
                          Integer publicationYear,
                          String documentType,
                          Long categoryId,
                          List<String> tagIds,
                          String abstractText,
                          String language);

    /**
     * 文献详情（含文件、解析记录、分析、笔记、相关文献、最新任务）
     */
    DocumentDetailVO getDocumentDetail(Long documentId);

    /**
     * 修改文献元数据
     */
    DocumentVO updateDocument(Long documentId, DocumentUpdateDTO dto);

    /**
     * 删除文献（逻辑删除文献，物理删除标签与收藏关联）
     */
    void deleteDocument(Long documentId);

    /**
     * 当前解析版本的正文
     */
    DocumentContentVO getDocumentContent(Long documentId);

    /**
     * 发起 AI 分析任务
     *
     * @return taskId 与 analysisType
     */
    Map<String, Object> analyzeDocument(Long documentId, AnalyzeRequestDTO dto);

    /**
     * 重新解析（新增解析版本，不覆盖当前版本）
     */
    ReparseResultVO reparseDocument(Long documentId);

    /**
     * 单篇文献的相似文献推荐
     *
     * <p>供推荐中心、文献对比等模块复用；最多返回 8 条。</p>
     *
     * @param documentId 文献ID
     * @param limit      期望条数（会被限制在 1-8）
     */
    List<RecommendationItemVO> recommendations(Long documentId, int limit);

    /**
     * 读取当前文献的原始 PDF（受控下载，调用方必须已完成归属校验）
     */
    OriginalPdf loadOriginalPdf(Long documentId);

    /**
     * 原始 PDF 文件名与内容
     */
    record OriginalPdf(String fileName, Resource resource) {
    }
}
