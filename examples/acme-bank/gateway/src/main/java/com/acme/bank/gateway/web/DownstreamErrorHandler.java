package com.acme.bank.gateway.web;

import com.acme.bank.gateway.client.DownstreamClientErrorException;
import com.acme.web.error.ApiException;
import com.acme.web.error.ProblemExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Translates a downstream {@link HttpClientErrorException} (4xx from the transfers service) into a
 * problem+json carrying the SAME status, so a downstream 400 stays 400 / 409 stays 409. Without this
 * the uncaught {@code HttpClientErrorException} would surface as a 500.
 *
 * <p>Resilience4j is configured to ignore {@code HttpClientErrorException}, so a 4xx is neither
 * retried nor counted toward opening the circuit — only 5xx/timeouts/open-circuit become 503.
 * Rendering reuses the shared {@link ProblemExceptionHandler} so the body shape matches every other
 * gateway error.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DownstreamErrorHandler {

    private final ProblemExceptionHandler problems;

    public DownstreamErrorHandler(ProblemExceptionHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ProblemDetail handleDownstreamClientError(HttpClientErrorException ex) {
        ApiException translated = new DownstreamClientErrorException(ex);
        return problems.handleApiException(translated);
    }
}
