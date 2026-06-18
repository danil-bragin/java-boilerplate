package com.acme.bank.gateway.web;

import com.acme.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Stable, machine-readable error contract for the gateway edge. */
public enum GatewayErrorCode implements ErrorCode {
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "Transfer not found"),
    TRANSFERS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Transfers service unavailable");

    private final HttpStatus status;
    private final String title;

    GatewayErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
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
        return title;
    }
}
