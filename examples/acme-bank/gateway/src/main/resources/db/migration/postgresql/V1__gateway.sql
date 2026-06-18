CREATE TABLE transfer_view (
    transfer_id            VARCHAR(64) PRIMARY KEY,
    status                 VARCHAR(32) NOT NULL,
    status_rank            INT NOT NULL,
    amount_value           NUMERIC(38,18) NOT NULL,
    amount_asset           VARCHAR(16) NOT NULL,
    source_account_id      VARCHAR(64) NOT NULL,
    destination_account_id VARCHAR(64) NOT NULL,
    failure_reason         VARCHAR(64),
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL
);

CREATE TABLE processed_messages (
    listener     VARCHAR(128) NOT NULL,
    message_id   VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (listener, message_id)
);
