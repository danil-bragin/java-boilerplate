package com.acme.bank.accounts.application;

public record PostTransferResult(String transferId, boolean posted, String reason) {
    public static PostTransferResult posted(String transferId) {
        return new PostTransferResult(transferId, true, null);
    }

    public static PostTransferResult rejected(String transferId, String reason) {
        return new PostTransferResult(transferId, false, reason);
    }
}
