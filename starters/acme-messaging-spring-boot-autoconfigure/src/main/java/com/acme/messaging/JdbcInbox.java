package com.acme.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

/**
 * {@link Inbox} backed by a {@code processed_messages} table.
 *
 * <p>BANK-19b: dedup is done with a CONFLICT-IGNORING upsert that reports whether a row was inserted,
 * NOT by catching a {@code DuplicateKeyException}. The old exception-driven form was correct for a
 * per-record transaction (the failing INSERT aborted that one record's tx, which then rolled back), but
 * it BREAKS under BATCH listeners: on Postgres a constraint violation aborts the WHOLE surrounding
 * transaction (SQLSTATE 25P02 "current transaction is aborted"), so a duplicate anywhere in a batch would
 * poison every other record's writes in that batch. The conflict-ignoring upsert never raises, so a
 * duplicate is a clean no-op (returns {@code false}) and the rest of the batch commits. Idempotency
 * semantics are unchanged: exactly the first {@code (listener, messageId)} returns {@code true}.
 */
public class JdbcInbox implements Inbox {

    private final JdbcTemplate jdbc;
    private final String insertSql;

    public JdbcInbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insertSql = insertSqlFor(jdbc);
    }

    @Override
    public boolean firstTime(String listener, String messageId) {
        // update() returns the affected row count: 1 on first insert, 0 when the (listener, messageId)
        // primary key already exists (the conflict is ignored, never raised) — so a duplicate, including
        // one within the same batch transaction, is a clean no-op that does not poison the transaction.
        int inserted = jdbc.update(insertSql, listener, messageId, Timestamp.from(Instant.now()));
        return inserted > 0;
    }

    /**
     * Pick the conflict-ignoring INSERT for the database. Postgres uses {@code ON CONFLICT DO NOTHING};
     * Oracle has no such clause, so use a {@code MERGE} that inserts only when the row is absent. Both are
     * race-safe (the PK enforces single-insert) and, crucially, neither aborts the surrounding transaction
     * on a duplicate.
     */
    private static String insertSqlFor(JdbcTemplate jdbc) {
        String product = databaseProduct(jdbc);
        if (product != null && product.toLowerCase().contains("oracle")) {
            return "MERGE INTO processed_messages t"
                    + " USING (SELECT ? AS listener, ? AS message_id, ? AS processed_at FROM dual) s"
                    + " ON (t.listener = s.listener AND t.message_id = s.message_id)"
                    + " WHEN NOT MATCHED THEN INSERT (listener, message_id, processed_at)"
                    + " VALUES (s.listener, s.message_id, s.processed_at)";
        }
        // Postgres (and the H2-in-Postgres-mode tests): conflict-ignoring upsert.
        return "INSERT INTO processed_messages(listener, message_id, processed_at) VALUES (?, ?, ?)"
                + " ON CONFLICT (listener, message_id) DO NOTHING";
    }

    private static String databaseProduct(JdbcTemplate jdbc) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            return null;
        }
        try {
            Object name =
                    JdbcUtils.extractDatabaseMetaData(dataSource, java.sql.DatabaseMetaData::getDatabaseProductName);
            return name == null ? null : name.toString();
        } catch (MetaDataAccessException e) {
            return null;
        }
    }
}
