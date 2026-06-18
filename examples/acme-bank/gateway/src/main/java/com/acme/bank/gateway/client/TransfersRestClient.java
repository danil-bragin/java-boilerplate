package com.acme.bank.gateway.client;

import com.acme.bank.gateway.api.dto.CreateTransferRequest;

/**
 * Out-adapter port to the downstream {@code transfers} service. Implementations forward the create
 * request (with the caller's {@code Idempotency-Key}) and return the created transfer id.
 */
@FunctionalInterface
public interface TransfersRestClient {

    /**
     * Forward a create-transfer request to the transfers service.
     *
     * @return the created transfer id
     */
    String create(CreateTransferRequest request, String idempotencyKey);
}
