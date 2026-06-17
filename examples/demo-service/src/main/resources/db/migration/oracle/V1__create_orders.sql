CREATE SEQUENCE order_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE orders (
    id         NUMBER(19)    NOT NULL PRIMARY KEY,
    sku        VARCHAR2(64)  NOT NULL,
    quantity   NUMBER(10)    NOT NULL,
    created_at TIMESTAMP(6)  NOT NULL,
    updated_at TIMESTAMP(6)  NOT NULL,
    version    NUMBER(19)    NOT NULL
);
