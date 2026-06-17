package com.acme.money;

/** An asset (currency or crypto): a stable code plus its minor-unit scale (decimal places). */
public record Asset(String code, int scale) {

    public Asset {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("asset code is required");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("asset scale must be >= 0");
        }
    }
}
