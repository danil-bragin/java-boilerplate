package com.acme.demo;

import com.acme.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum DemoErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found");

    private final HttpStatus status;
    private final String defaultTitle;

    DemoErrorCode(HttpStatus status, String defaultTitle) {
        this.status = status;
        this.defaultTitle = defaultTitle;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultTitle() {
        return defaultTitle;
    }
}
