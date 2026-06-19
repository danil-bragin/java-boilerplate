package com.acme.bank.transfers.adapter.in.web;

import com.acme.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum TransferErrorCode implements ErrorCode {
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "Transfer not found"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");

    private final HttpStatus status;
    private final String title;

    TransferErrorCode(HttpStatus status, String title) {
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
