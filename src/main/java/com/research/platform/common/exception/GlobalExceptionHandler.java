package com.research.platform.common.exception;

import com.research.platform.common.result.Result;
import com.research.platform.infrastructure.ai.AiServiceException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 处理业务异常
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(
            BusinessException e
    ) {
        return Result.error(e.getCode(), e.getMessage());
    }

    // 处理DTO参数校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(
            MethodArgumentNotValidException e
    ) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("参数校验失败");

        return Result.error(400, message);
    }

    // 处理其他参数校验异常
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(
            ConstraintViolationException e
    ) {
        return Result.error(400, e.getMessage());
    }

    // 处理上传文件超限
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超出大小限制：{}", e.getMessage());
        return Result.error(413, "文件大小超出限制，单个 PDF 不得超过 50MB");
    }

    // 处理缺少上传文件
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public Result<Void> handleMissingPart(Exception e) {
        return Result.error(400, "请求缺少必要参数：" + e.getMessage());
    }

    // 处理方法级权限不足（@PreAuthorize）
    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限不足：{}", e.getMessage());
        return Result.error(403, "没有权限执行该操作");
    }

    // 处理 AI 服务不可用
    @ExceptionHandler(AiServiceException.class)
    public Result<Void> handleAiServiceException(AiServiceException e) {
        log.warn("AI 服务调用失败：{}", e.getMessage());
        return Result.error(503, e.getMessage());
    }

    // 处理请求体 JSON 格式错误
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return Result.error(400, "请求体格式不正确，请检查 JSON 是否合法");
    }

    // 处理路径/查询参数类型不匹配（如 id=abc）
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.error(400, "参数类型不正确：" + e.getName());
    }

    // 处理唯一键冲突等数据完整性问题
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突：{}", e.getMessage());
        return Result.error(409, "数据已存在或与现有记录冲突");
    }

    // 处理外键约束等数据完整性问题
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result<Void> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("数据完整性校验失败：{}", e.getMessage());
        return Result.error(409, "数据存在关联关系，操作被拒绝");
    }

    // 处理未知异常
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {

        // 记录完整异常信息
        log.error("系统发生未处理异常", e);

        return Result.error(500, "服务器内部错误");
    }
}