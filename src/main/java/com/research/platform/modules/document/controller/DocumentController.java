package com.research.platform.modules.document.controller;

import com.research.platform.common.result.PageResult;
import com.research.platform.common.result.Result;
import com.research.platform.common.vo.RecommendationItemVO;
import com.research.platform.modules.document.dto.AnalyzeRequestDTO;
import com.research.platform.modules.document.dto.DocumentQueryDTO;
import com.research.platform.modules.document.dto.DocumentUpdateDTO;
import com.research.platform.modules.document.service.IDocumentService;
import com.research.platform.modules.document.vo.DocumentContentVO;
import com.research.platform.modules.document.vo.DocumentDetailVO;
import com.research.platform.modules.document.vo.DocumentVO;
import com.research.platform.modules.document.vo.ReparseResultVO;
import com.research.platform.modules.document.vo.UploadResultVO;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 文献接口
 *
 * <p>路径与前端 {@code src/api/document.ts} 一一对应；返回值统一包一层
 * {@link Result}，分页使用 {@link PageResult}。</p>
 */
@Validated
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IDocumentService documentService;

    public DocumentController(IDocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 文献列表（分页 + 筛选 + 排序）
     */
    @GetMapping
    public Result<PageResult<DocumentVO>> listDocuments(@Valid DocumentQueryDTO query) {
        return Result.success(documentService.listDocuments(query));
    }

    /**
     * 上传文献，创建解析任务
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<UploadResultVO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "authors", required = false) String authors,
            @RequestParam(value = "keywords", required = false) String keywords,
            @RequestParam(value = "journal", required = false) String journal,
            @RequestParam(value = "doi", required = false) String doi,
            @RequestParam(value = "publicationYear", required = false) Integer publicationYear,
            @RequestParam(value = "documentType", required = false) String documentType,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "tagIds", required = false) List<String> tagIds,
            @RequestParam(value = "abstract", required = false) String abstractText,
            @RequestParam(value = "language", required = false) String language) {

        UploadResultVO uploadResult = documentService.upload(
                file, title, authors, keywords, journal, doi, publicationYear,
                documentType, categoryId, tagIds, abstractText, language);

        return Result.success(uploadResult);
    }

    /**
     * 文献详情
     */
    @GetMapping("/{id}")
    public Result<DocumentDetailVO> getDocument(@PathVariable("id") Long id) {
        return Result.success(documentService.getDocumentDetail(id));
    }

    /**
     * 修改文献元数据
     */
    @PutMapping("/{id}")
    public Result<DocumentVO> updateDocument(@PathVariable("id") Long id,
                                             @Valid @RequestBody DocumentUpdateDTO dto) {
        return Result.success(documentService.updateDocument(id, dto));
    }

    /**
     * 删除文献
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteDocument(@PathVariable("id") Long id) {
        documentService.deleteDocument(id);
        return Result.success(null);
    }

    /**
     * 当前解析版本的正文
     */
    @GetMapping("/{id}/content")
    public Result<DocumentContentVO> getContent(@PathVariable("id") Long id) {
        return Result.success(documentService.getDocumentContent(id));
    }

    /**
     * 原始 PDF（受控下载；前端带 JWT 读取后用 blob 打开）
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> getOriginalFile(@PathVariable("id") Long id) {
        IDocumentService.OriginalPdf pdf = documentService.loadOriginalPdf(id);

        ContentDisposition disposition = ContentDisposition.inline()
                .filename(pdf.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(pdf.resource());
    }

    /**
     * 发起 AI 分析
     */
    @PostMapping("/{id}/analyze")
    public Result<Map<String, Object>> analyze(@PathVariable("id") Long id,
                                               @Valid @RequestBody(required = false) AnalyzeRequestDTO dto) {
        return Result.success(documentService.analyzeDocument(id, dto));
    }

    /**
     * 重新解析（新增解析版本）
     */
    @PostMapping("/{id}/reparse")
    public Result<ReparseResultVO> reparse(@PathVariable("id") Long id) {
        return Result.success(documentService.reparseDocument(id));
    }

    /**
     * 单篇文献的相似文献推荐（最多 8 条）
     */
    @GetMapping("/{id}/recommendations")
    public Result<List<RecommendationItemVO>> recommendations(@PathVariable("id") Long id) {
        return Result.success(documentService.recommendations(id, 8));
    }
}
