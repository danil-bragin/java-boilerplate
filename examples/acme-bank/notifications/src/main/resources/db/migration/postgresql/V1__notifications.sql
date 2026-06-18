CREATE TABLE notification (
    id          BIGSERIAL    NOT NULL PRIMARY KEY,
    transfer_id VARCHAR(64)  NOT NULL,
    channel     VARCHAR(32)  NOT NULL,
    message     TEXT         NOT NULL,
    status      VARCHAR(32)  NOT NULL
);

CREATE INDEX idx_notification_transfer ON notification (transfer_id);

CREATE TABLE processed_messages (
    listener     VARCHAR(128) NOT NULL,
    message_id   VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (listener, message_id)
);
