-- A system equity account that funds opening deposits so the ledger stays balanced (Σ=0).
-- Every opening deposit credits the new account and debits this one, so its balance goes negative —
-- it is the bank's equity / source of funds, not a customer account.
INSERT INTO account(id, iban, status) VALUES ('bank-equity', 'EQUITY-0000', 'OPEN');
