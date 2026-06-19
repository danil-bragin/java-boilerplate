-- BANK-22 Fix 1: per-source fast-path velocity guard.
-- A creation timestamp so transfers can count a source's RECENT transfers in its OWN DB (no Kafka hop)
-- before taking the fast-path. Defaults to now() so existing rows get a sensible value; the column is
-- DB-managed (Hibernate never writes it — insertable/updatable=false on the entity), so the default fills it.
ALTER TABLE transfer ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();

-- Index the velocity count: count(*) where source_account_id = ? and created_at > ?.
CREATE INDEX idx_transfer_source_created ON transfer (source_account_id, created_at);
