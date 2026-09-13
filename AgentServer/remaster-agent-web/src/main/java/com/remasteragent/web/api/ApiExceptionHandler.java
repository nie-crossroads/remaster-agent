package com.remasteragent.web.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * 把异常翻译成统一的 JSON 错误体。
 *
 * <p>没有这一层的话，Spring 默认会返回一个 HTML 错误页（或结构各异的 JSON），
 * 前端就得为「400 长这样、404 长那样、500 又长一样」分别写解析分支。
 * 统一成 {@code {"error": "...", "at": "..."}} 之后，前端只需要读一个字段。
 *
 * <p>错误消息刻意保持<b>对使用者可读</b>（比如「entryFile 必须位于 projectRoot 内」），
 * 而不是把异常类名和堆栈丢出去 —— 这个接口的调用方是人和前端，
 * 不是需要按异常类型分支的程序。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 统一错误体。 */
    public record ApiError(String error, Instant at) {
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleNotFound(NotFoundException e) {
        return new ApiError(e.getMessage(), Instant.now());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBadRequest(RuntimeException e) {
        return new ApiError(e.getMessage(), Instant.now());
    }

    /** Bean Validation 失败：把「字段 → 原因」拼成一句人能看懂的话。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::describe)
                .collect(Collectors.joining("；"));
        return new ApiError(detail.isBlank() ? "请求参数不合法" : detail, Instant.now());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleUnexpected(Exception e) {
        log.error("未预期的接口异常", e);
        // 对外只给一句概述 + 异常类型，不暴露堆栈：堆栈只在服务端日志里
        return new ApiError("服务端异常: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " - " + e.getMessage()), Instant.now());
    }

    private static String describe(FieldError error) {
        String message = error.getDefaultMessage();
        return error.getField() + (message == null || message.isBlank() ? " 不合法" : ": " + message);
    }
}
