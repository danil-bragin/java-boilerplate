package com.acme.bank.gateway.client;

import com.acme.web.error.ApiException;
import com.acme.web.error.ErrorCode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Carries a downstream client error (4xx) so it surfaces to the caller with the SAME status (e.g. a
 * transfers-side 400 stays 400, a 409 stays 409) instead of being retried, tripping the circuit, and
 * masked as a 503. Distinct from {@link GatewayUnavailableException} which is for 5xx/timeouts/open
 * circuit.
 */
public class DownstreamClientErrorException extends ApiException {

    public DownstreamClientErrorException(HttpClientErrorException cause) {
        super(new DownstreamErrorCode(HttpStatus.valueOf(cause.getStatusCode().value())), Map.of(), cause);
    }

    /** Ad-hoc {@link ErrorCode} echoing the downstream 4xx status. */
    private record DownstreamErrorCode(HttpStatus status) implements ErrorCode {
        @Override
        public String code() {
            return "TRANSFERS_REJECTED";
        }

        @Override
        public HttpStatus status() {
            return status;
        }

        @Override
        public String defaultTitle() {
            return "Transfers service rejected the request";
        }
    }
}
