package com.acme.web.error;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders application errors as RFC 9457 problem+json. Owns the built-in Spring MVC exceptions
 * (by extending {@link ResponseEntityExceptionHandler}) and is ordered highest-precedence so it
 * wins over Boot's default handler.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        pd.setTitle(ex.errorCode().defaultTitle());
        pd.setType(URI.create(
                "https://errors.acme.com/" + ex.errorCode().code().toLowerCase().replace('_', '-')));
        pd.setProperty("code", ex.errorCode().code());
        pd.setProperty("params", ex.params());
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            pd.setProperty("traceId", traceId);
        }
        return pd;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ex.getBody();
        pd.setProperty("code", "VALIDATION_FAILED");
        List<Map<String, Object>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.<String, Object>of(
                        "field", fe.getField(),
                        "rejectedValue", String.valueOf(fe.getRejectedValue()),
                        "message", Objects.requireNonNullElse(fe.getDefaultMessage(), "invalid")))
                .toList();
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }
}
