CREATE TABLE account (
    id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    iban   VARCHAR(34)  NOT NULL,
    status VARCHAR(16)  NOT NULL
);

CREATE SEQUENCE ledger_entry_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE ledger_entry (
    id          BIGINT        NOT NULL PRIMARY KEY,
    transfer_id VARCHAR(64)   NOT NULL,
    account_id  VARCHAR(64)   NOT NULL,
    amount      NUMERIC(38,18) NOT NULL,
    asset       VARCHAR(16)   NOT NULL
);

CREATE INDEX idx_ledger_entry_account ON ledger_entry (account_id, asset);
CREATE INDEX idx_ledger_entry_transfer ON ledger_entry (transfer_id);
