-- Idempotency anchor for account opening: one row per client request id, mapping it to the
-- account that was opened. A retry with the same request id returns the same account (no double-open).
CREATE TABLE open_request (
    request_id VARCHAR2(128) NOT NULL PRIMARY KEY,
    account_id VARCHAR2(64)  NOT NULL
);
