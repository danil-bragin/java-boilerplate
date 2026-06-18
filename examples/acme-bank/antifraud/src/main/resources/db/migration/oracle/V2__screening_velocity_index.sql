-- BANK-19b: index the antifraud velocity check `countBySourceAccountIdAndApprovedTrue` (the #1 Postgres
-- cost under load — see the postgresql variant). Oracle has no partial-index WHERE clause, so index the
-- (source_account_id, approved) pair so the count is satisfied from the index. Money-safe read-path
-- optimization; no semantic change.
CREATE INDEX idx_screening_decision_source_approved
    ON screening_decision (source_account_id, approved);
