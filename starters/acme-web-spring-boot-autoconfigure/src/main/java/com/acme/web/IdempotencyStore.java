package com.acme.web;

import java.util.Optional;

/** Stores and replays completed responses keyed by an Idempotency-Key. Overridable (e.g. Redis-backed). */
public interface IdempotencyStore {

    Optional<StoredResponse> find(String key);

    void save(String key, StoredResponse response);

    record StoredResponse(int status, String contentType, byte[] body) {}
}
