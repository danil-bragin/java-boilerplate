CREATE TABLE shedlock (
    name       VARCHAR2(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3)  NOT NULL,
    locked_at  TIMESTAMP(3)  NOT NULL,
    locked_by  VARCHAR2(255) NOT NULL
);
