package com.research.platform.modules.report.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.platform.common.dto.PageQueryDTO;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.common.result.PageResult;
import com.research.platform.common.result.Result;
import com.research.platform.modules.report.dto.ReportCreateDTO;
import com.research.platform.modules.report.service.IReportService;
import com.research.platform.modules.report.vo.ExportFile;
import com.research.platform.modules.report.vo.ReportVO;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 分析报告接口
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final IReportService reportService;

    private final ObjectMapper objectMapper;

    public ReportController(IReportService reportService, ObjectMapper objectMapper) {
        this.reportService = reportService;
        this.objectMapper = objectMapper;
    }

    /**
     * 报告列表
     */
    @GetMapping
    public Result<PageResult<ReportVO>> page(@ModelAttribute PageQueryDTO query) {
        return Result.success(reportService.page(query));
    }

    /**
     * 报告详情
     */
    @GetMapping("/{id}")
    public Result<ReportVO> detail(@PathVariable Long id) {
        return Result.success(reportService.detail(id));
    }

    /**
     * 生成报告
     */
    @PostMapping
    public Result<ReportVO> create(@Valid @RequestBody ReportCreateDTO dto) {
        return Result.success(reportService.create(dto));
    }

    /**
     * 导出报告（文件下载，不包 Result）
     *
     * <p>{@code format=markdown|md|docx}，缺省 markdown。Content-Disposition 按 RFC 5987
     * 同时输出 {@code filename} 与 {@code filename*=UTF-8''...}，中文文件名不会乱码。</p>
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable("id") Long id,
            @RequestParam(value = "format", required = false, defaultValue = "markdown") String format) {
        try {
            ExportFile file = reportService.export(id, format);

            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(file.getFileName(), StandardCharsets.UTF_8)
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.getContentType()))
                    .contentLength(file.getContent().length)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .body(file.getContent());
        } catch (BusinessException e) {
            // 下载接口返回原始字节，异常也按 JSON Result + 对应 HTTP 状态码输出
            return errorResponse(e);
        }
    }

    /**
     * 删除报告（逻辑删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        reportService.delete(id);
        return Result.success(null);
    }

    /**
     * 把业务异常转换为带正确 HTTP 状态码的 JSON 响应（仅用于文件下载接口）
     */
    private ResponseEntity<byte[]> errorResponse(BusinessException e) {
        int code = e.getCode() == null ? 400 : e.getCode();
        HttpStatus status = HttpStatus.resolve(code);
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(Result.error(code, e.getMessage()));
        } catch (JsonProcessingException ex) {
            body = new byte[0];
        }

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
