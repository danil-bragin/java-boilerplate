package com.acme.bank.transfers.adapter.out.posting;

/**
 * Outcome of a synchronous posting attempt against accounts {@code POST /internal/postings}. The
 * critical money-safety distinction is between the two failure shapes:
 *
 * <ul>
 *   <li>{@link Outcome#POSTED} / {@link Outcome#REJECTED} — accounts answered (HTTP 200). The money
 *       mutation either happened (POSTED) or provably did not (REJECTED, with a reason). Terminalize.
 *   <li>{@link Outcome#NOT_MADE} — the call was NOT made (circuit open / connection refused). No
 *       posting could have happened, so it is SAFE to fall back to the async saga.
 *   <li>{@link Outcome#UNKNOWN} — the request may have reached accounts but no clean answer came back
 *       (post-send timeout). Whether money moved is UNKNOWN. The handler MUST NOT guess: it leaves the
 *       transfer {@code POSTING} for the BANK-12 reconciler (which queries accounts' ledger) to resolve.
 * </ul>
 */
public record SyncPostResult(Outcome outcome, String reason) {

    public enum Outcome {
        POSTED,
        REJECTED,
        NOT_MADE,
        UNKNOWN
    }

    public static SyncPostResult posted() {
        return new SyncPostResult(Outcome.POSTED, null);
    }

    public static SyncPostResult rejected(String reason) {
        return new SyncPostResult(Outcome.REJECTED, reason);
    }

    public static SyncPostResult notMade() {
        return new SyncPostResult(Outcome.NOT_MADE, null);
    }

    public static SyncPostResult unknown() {
        return new SyncPostResult(Outcome.UNKNOWN, null);
    }
}
