CREATE TABLE transfer_view (
    transfer_id            VARCHAR(64) PRIMARY KEY,
    status                 VARCHAR(32) NOT NULL,
    status_rank            INT NOT NULL,
    -- Facts (amount/accounts/created_at) are carried only by TransferRequested. A terminal event
    -- consumed before TransferRequested seeds the row with these NULL until REQUESTED replays.
    amount_value           NUMERIC(38,18),
    amount_asset           VARCHAR(16),
    source_account_id      VARCHAR(64),
    destination_account_id VARCHAR(64),
    failure_reason         VARCHAR(64),
    created_at             TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ NOT NULL
);

CREATE TABLE processed_messages (
    listener     VARCHAR(128) NOT NULL,
    message_id   VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (listener, message_id)
);
