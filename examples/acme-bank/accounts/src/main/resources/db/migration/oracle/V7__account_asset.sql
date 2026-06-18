-- Persist each account's currency on the account row itself, so a no-deposit account reports its
-- real currency (not a ledger-derived guess) and a per-account single-asset invariant can be enforced.
ALTER TABLE account ADD asset VARCHAR2(16);

-- Backfill existing rows; the bank-equity seed is the USD equity source.
UPDATE account SET asset = 'USD' WHERE asset IS NULL;

ALTER TABLE account MODIFY asset VARCHAR2(16) NOT NULL;
