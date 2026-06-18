CREATE TABLE screening_decision (
    transfer_id       VARCHAR(64)  NOT NULL PRIMARY KEY,
    source_account_id VARCHAR(64)  NOT NULL,
    approved          BOOLEAN      NOT NULL,
    reason            VARCHAR(64)
);

CREATE TABLE processed_messages (
    listener     VARCHAR(128) NOT NULL,
    message_id   VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (listener, message_id)
);

CREATE TABLE event_publication (
    id               UUID         NOT NULL PRIMARY KEY,
    listener_id      TEXT         NOT NULL,
    serialized_event TEXT         NOT NULL,
    event_type       TEXT         NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE
);
