package com.remasteragent.web.api;

/** 请求的资源不存在 —— 由 {@link ApiExceptionHandler} 映射成 404。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
