package com.acme.bank.gateway.projection;

/**
 * Status of a transfer as seen by the read model, with a monotonic {@link #rank()} used to guard
 * out-of-order saga events. A projection only advances when the incoming rank is greater than or
 * equal to the stored rank, so redelivered/stale events never regress a transfer's status.
 */
public enum TransferStatus {
    REQUESTED(0),
    // SCREENING and POSTING ranks are reserved for future intermediate-status producers; the gateway
    // projection currently emits REQUESTED -> APPROVED -> COMPLETED/FAILED. Kept because the OpenAPI
    // contract enum lists them.
    SCREENING(1),
    APPROVED(2),
    POSTING(3),
    COMPLETED(4),
    FAILED(4);

    private final int rank;

    TransferStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
