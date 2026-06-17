package com.acme.web.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error contract. Implemented per-service (usually as an enum).
 * The {@code code} is locale-independent and is what clients branch on — never the detail text.
 */
public interface ErrorCode {

    /** Stable UPPER_SNAKE identifier, e.g. {@code ORDER_NOT_FOUND}. */
    String code();

    /** HTTP status this error maps to. */
    HttpStatus status();

    /** Human-readable default title (locale-independent fallback). */
    String defaultTitle();
}
