CREATE SEQUENCE order_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE orders (
    id         BIGINT       NOT NULL PRIMARY KEY,
    sku        VARCHAR(64)  NOT NULL,
    quantity   INTEGER      NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version    BIGINT       NOT NULL
);
