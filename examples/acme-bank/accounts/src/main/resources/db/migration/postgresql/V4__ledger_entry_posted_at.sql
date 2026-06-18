-- Statement queries need a posting time per ledger entry to order lines and bound by [from, to).
ALTER TABLE ledger_entry ADD COLUMN posted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();

CREATE INDEX idx_ledger_entry_account_time ON ledger_entry (account_id, asset, posted_at);
