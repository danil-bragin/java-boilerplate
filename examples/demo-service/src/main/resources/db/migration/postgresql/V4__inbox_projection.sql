CREATE TABLE processed_messages (
    listener     VARCHAR(128) NOT NULL,
    message_id   VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (listener, message_id)
);

CREATE TABLE order_projection (
    order_id BIGINT      NOT NULL PRIMARY KEY,
    sku      VARCHAR(64) NOT NULL,
    quantity INTEGER     NOT NULL
);
