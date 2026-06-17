package com.acme.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link Inbox} backed by a {@code processed_messages} table; duplicates trip the primary key. */
public class JdbcInbox implements Inbox {

    private final JdbcTemplate jdbc;

    public JdbcInbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean firstTime(String listener, String messageId) {
        try {
            jdbc.update(
                    "INSERT INTO processed_messages(listener, message_id, processed_at) VALUES (?, ?, ?)",
                    listener,
                    messageId,
                    Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
