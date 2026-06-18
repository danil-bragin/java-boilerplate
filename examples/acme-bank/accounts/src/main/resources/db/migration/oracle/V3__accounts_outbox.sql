CREATE TABLE processed_messages (
    listener     VARCHAR2(128) NOT NULL,
    message_id   VARCHAR2(128) NOT NULL,
    processed_at TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (listener, message_id)
);

CREATE TABLE event_publication (
    id               RAW(16)                     NOT NULL PRIMARY KEY,
    listener_id      VARCHAR2(512)               NOT NULL,
    serialized_event CLOB                        NOT NULL,
    event_type       VARCHAR2(512)               NOT NULL,
    publication_date TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP(6) WITH TIME ZONE
);
