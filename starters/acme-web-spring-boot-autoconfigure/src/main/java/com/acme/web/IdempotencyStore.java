package com.acme.web;

import java.util.Optional;

public interface IdempotencyStore {

    /** A completed response for the key, if any. */
    Optional<StoredResponse> find(String key);

    /** Atomically reserve the key as in-progress. Returns true only for the FIRST caller. */
    boolean reserve(String key);

    /** Mark the key complete with its response. */
    void complete(String key, StoredResponse response);

    /** Release a reservation (e.g. on a non-cacheable outcome) so it can be retried. */
    void release(String key);

    record StoredResponse(int status, String contentType, byte[] body) {}
}
