package com.acme.bank.accounts.adapter.in.web;

import com.acme.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AccountErrorCode implements ErrorCode {
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Account not found"),
    ILLEGAL_ACCOUNT_TRANSITION(HttpStatus.CONFLICT, "Illegal account lifecycle transition");

    private final HttpStatus status;
    private final String title;

    AccountErrorCode(HttpStatus status, String title) {
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
