-- BANK-12: the money-safe stuck-saga reconciler.

-- A last-touched timestamp so the reconciler can find transfers stuck in a non-terminal state
-- (updated_at < cutoff). Defaults to now() so existing rows are not immediately "stuck".
ALTER TABLE transfer ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();

-- Index the sweep: non-terminal status + age.
CREATE INDEX idx_transfer_status_updated ON transfer (status, updated_at);

-- ShedLock single-runner lock table (the reconciler runs on exactly one replica per tick).
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
