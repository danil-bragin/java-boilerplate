package com.acme.web.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Application exception carrying a stable {@link ErrorCode} and locale-independent params. */
public class ApiException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> params;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, Map.of(), null);
    }

    public ApiException(ErrorCode errorCode, Map<String, Object> params) {
        this(errorCode, params, null);
    }

    public ApiException(ErrorCode errorCode, Map<String, Object> params, Throwable cause) {
        super(errorCode.defaultTitle(), cause);
        this.errorCode = errorCode;
        this.params = params == null ? Map.of() : Map.copyOf(params);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public HttpStatus status() {
        return errorCode.status();
    }

    public Map<String, Object> params() {
        return params;
    }
}
