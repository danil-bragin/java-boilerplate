package com.acme.bank.transfers.application;

import com.acme.bank.transfers.domain.TransferStatus;

/**
 * Outcome of initiating a transfer. Carries the resolved status so the REST layer can return the
 * right code/body:
 *
 * <ul>
 *   <li>fast-path terminal (COMPLETED / FAILED) → {@code 200} with the final status;
 *   <li>slow-path or UNKNOWN (REQUESTED / POSTING) → {@code 202} (the async saga / reconciler resolves).
 * </ul>
 *
 * <p>{@code terminal} is true only for COMPLETED/FAILED (synchronous fast-path outcomes). A POSTING
 * result (timeout-after-send) is NOT terminal — the reconciler decides it later.
 */
public record InitiateTransferResult(String transferId, TransferStatus status, String failureReason) {

    public boolean terminal() {
        return status == TransferStatus.COMPLETED || status == TransferStatus.FAILED;
    }

    public static InitiateTransferResult requested(String transferId) {
        return new InitiateTransferResult(transferId, TransferStatus.REQUESTED, null);
    }

    public static InitiateTransferResult completed(String transferId) {
        return new InitiateTransferResult(transferId, TransferStatus.COMPLETED, null);
    }

    public static InitiateTransferResult failed(String transferId, String reason) {
        return new InitiateTransferResult(transferId, TransferStatus.FAILED, reason);
    }

    public static InitiateTransferResult posting(String transferId) {
        return new InitiateTransferResult(transferId, TransferStatus.POSTING, null);
    }
}
