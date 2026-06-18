CREATE TABLE notification (
    id          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id VARCHAR2(64)  NOT NULL,
    channel     VARCHAR2(32)  NOT NULL,
    message     CLOB          NOT NULL,
    status      VARCHAR2(32)  NOT NULL
);

CREATE INDEX idx_notification_transfer ON notification (transfer_id);

CREATE TABLE processed_messages (
    listener     VARCHAR2(128) NOT NULL,
    message_id   VARCHAR2(128) NOT NULL,
    processed_at TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (listener, message_id)
);
