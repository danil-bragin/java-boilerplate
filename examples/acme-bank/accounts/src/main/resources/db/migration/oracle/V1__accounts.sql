CREATE TABLE account (
    id     VARCHAR2(64) NOT NULL PRIMARY KEY,
    iban   VARCHAR2(34) NOT NULL,
    status VARCHAR2(16) NOT NULL
);

CREATE SEQUENCE ledger_entry_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE ledger_entry (
    id          NUMBER(19)     NOT NULL PRIMARY KEY,
    transfer_id VARCHAR2(64)   NOT NULL,
    account_id  VARCHAR2(64)   NOT NULL,
    amount      NUMBER(38,18)  NOT NULL,
    asset       VARCHAR2(16)   NOT NULL
);

CREATE INDEX idx_ledger_entry_account ON ledger_entry (account_id, asset);
CREATE INDEX idx_ledger_entry_transfer ON ledger_entry (transfer_id);
