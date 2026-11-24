package com.research.platform.common.exception;

import com.research.platform.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    // 处理未知异常
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {

        // 记录完整异常信息
        log.error("系统发生未处理异常", e);

        return Result.error(500, "服务器内部错误");
    }
}