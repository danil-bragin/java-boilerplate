package com.acme.bank.gateway.client;

import com.acme.bank.gateway.web.GatewayErrorCode;
import com.acme.web.error.ApiException;
import java.util.Map;

/** Thrown when the downstream transfers service is unavailable (circuit open / retries exhausted). */
public class GatewayUnavailableException extends ApiException {

    public GatewayUnavailableException(Throwable cause) {
        super(GatewayErrorCode.TRANSFERS_UNAVAILABLE, Map.of(), cause);
    }
}
