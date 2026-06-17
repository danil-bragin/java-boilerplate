package com.acme.money;

/** Thrown when a binary money operation is attempted across differing assets. */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(Asset left, Asset right) {
        super("currency mismatch: " + left.code() + " vs " + right.code());
    }
}
