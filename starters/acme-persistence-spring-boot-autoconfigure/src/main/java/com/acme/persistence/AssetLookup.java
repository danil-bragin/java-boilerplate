package com.acme.persistence;

import com.acme.money.Asset;

/** Resolves an asset code to an {@link Asset} (e.g. {@code Assets::of}); keeps persistence decoupled. */
@FunctionalInterface
public interface AssetLookup {
    Asset resolve(String code);
}
