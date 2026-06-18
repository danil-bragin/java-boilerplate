CREATE TABLE transfer (
    id                     VARCHAR(64)    NOT NULL PRIMARY KEY,
    source_account_id      VARCHAR(64)    NOT NULL,
    destination_account_id VARCHAR(64)    NOT NULL,
    amount                 NUMERIC(38,18) NOT NULL,
    asset                  VARCHAR(16)    NOT NULL,
    requested_by           VARCHAR(128)   NOT NULL,
    status                 VARCHAR(16)    NOT NULL,
    failure_reason         VARCHAR(256)
);

CREATE TABLE event_publication (
    id               UUID         NOT NULL PRIMARY KEY,
    listener_id      TEXT         NOT NULL,
    serialized_event TEXT         NOT NULL,
    event_type       TEXT         NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE
);
