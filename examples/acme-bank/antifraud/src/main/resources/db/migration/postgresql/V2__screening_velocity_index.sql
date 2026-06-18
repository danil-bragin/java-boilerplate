-- BANK-19b: the antifraud velocity check `countBySourceAccountIdAndApprovedTrue` was the #1 Postgres
-- cost under load (diagnosed via pg_stat_statements: ~7x the next statement's total exec time, mean
-- ~0.6ms vs ~0.02ms for every other query) because screening_decision had ONLY a PK index on
-- transfer_id, forcing a SEQUENTIAL SCAN of the whole table per screen — cost grows with table size.
-- A partial index on the velocity predicate (source_account_id WHERE approved) turns that seq scan
-- into an index-only count. Money-safe: a read-path optimization; no semantic change to screening.
CREATE INDEX idx_screening_decision_source_approved
    ON screening_decision (source_account_id)
    WHERE approved;
