CREATE TABLE transfer (
    id                     VARCHAR2(64)   NOT NULL PRIMARY KEY,
    source_account_id      VARCHAR2(64)   NOT NULL,
    destination_account_id VARCHAR2(64)   NOT NULL,
    amount                 NUMBER(38,18)  NOT NULL,
    asset                  VARCHAR2(16)   NOT NULL,
    requested_by           VARCHAR2(128)  NOT NULL,
    status                 VARCHAR2(16)   NOT NULL,
    failure_reason         VARCHAR2(256)
);

CREATE TABLE event_publication (
    id               RAW(16)                     NOT NULL PRIMARY KEY,
    listener_id      VARCHAR2(512)               NOT NULL,
    serialized_event CLOB                        NOT NULL,
    event_type       VARCHAR2(512)               NOT NULL,
    publication_date TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP(6) WITH TIME ZONE
);
