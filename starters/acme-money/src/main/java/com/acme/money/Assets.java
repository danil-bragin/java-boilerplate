package com.acme.money;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-code registry of known assets (ISO-4217 fiat + crypto). No database lookup. */
public final class Assets {

    private static final ConcurrentMap<String, Asset> REGISTRY = new ConcurrentHashMap<>();

    public static final Asset USD = register(new Asset("USD", 2));
    public static final Asset EUR = register(new Asset("EUR", 2));
    public static final Asset JPY = register(new Asset("JPY", 0));
    public static final Asset BHD = register(new Asset("BHD", 3));
    public static final Asset ETH = register(new Asset("ETH", 18));
    public static final Asset USDC = register(new Asset("USDC", 6));

    private Assets() {}

    private static Asset register(Asset asset) {
        REGISTRY.put(asset.code(), asset);
        return asset;
    }

    /** Returns the registered asset for the code, or throws if unknown. */
    public static Asset of(String code) {
        Asset asset = REGISTRY.get(code);
        if (asset == null) {
            throw new IllegalArgumentException("unknown asset: " + code);
        }
        return asset;
    }

    public static Optional<Asset> find(String code) {
        return Optional.ofNullable(REGISTRY.get(code));
    }
}
