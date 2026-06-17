CREATE TABLE processed_messages (
    listener     VARCHAR2(128) NOT NULL,
    message_id   VARCHAR2(128) NOT NULL,
    processed_at TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (listener, message_id)
);

CREATE TABLE order_projection (
    order_id NUMBER(19)   NOT NULL PRIMARY KEY,
    sku      VARCHAR2(64) NOT NULL,
    quantity NUMBER(10)   NOT NULL
);
