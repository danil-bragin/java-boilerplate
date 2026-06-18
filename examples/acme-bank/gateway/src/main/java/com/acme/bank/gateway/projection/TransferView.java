package com.acme.bank.gateway.projection;

import com.acme.persistence.MoneyAmount;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * CQRS read model row for a transfer, built by consuming the saga's Avro events.
 *
 * <p>A row has two independent concerns: (a) immutable FACTS — amount, source/destination account,
 * createdAt — which ONLY the {@code TransferRequested} event carries; (b) STATUS — status, rank,
 * failureReason, updatedAt — advanced monotonically by whichever event has the highest rank seen so
 * far. Because the 4 topics have no cross-topic ordering, a terminal event may be consumed before
 * {@code TransferRequested}: such a row is {@link #seed seeded} with NULL facts and a terminal
 * status, then {@link #applyFacts} fills the facts when {@code TransferRequested} later replays.
 */
@Entity
@Table(name = "transfer_view")
public class TransferView {

    @Id
    @Column(name = "transfer_id")
    private String transferId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "status_rank", nullable = false)
    private int statusRank;

    // Facts (amount, source, destination, createdAt) are nullable: a row created from a terminal
    // event that arrived before TransferRequested has them null until REQUESTED replays.
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "amount_value", nullable = true)),
        @AttributeOverride(name = "asset", column = @Column(name = "amount_asset", nullable = true))
    })
    private MoneyAmount amount;

    @Column(name = "source_account_id", nullable = true)
    private String sourceAccountId;

    @Column(name = "destination_account_id", nullable = true)
    private String destinationAccountId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = true)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TransferView() {
        // for JPA
    }

    private TransferView(String transferId, TransferStatus status, int statusRank, Instant now) {
        this.transferId = transferId;
        this.status = status.name();
        this.statusRank = statusRank;
        this.updatedAt = now;
    }

    /**
     * Create a minimal row with the given status/rank and NULL facts. Used when an event is consumed
     * for a transfer whose {@code TransferRequested} (which carries the facts) has not arrived yet.
     */
    public static TransferView seed(String transferId, TransferStatus status, int rank, Instant now) {
        return new TransferView(transferId, status, rank, now);
    }

    /**
     * Fill the immutable facts, but only when they are still null (first writer wins — facts are
     * immutable and carried solely by {@code TransferRequested}). Idempotent across redeliveries.
     */
    public void applyFacts(MoneyAmount amount, String sourceAccountId, String destinationAccountId, Instant createdAt) {
        if (this.createdAt == null) {
            this.amount = amount;
            this.sourceAccountId = sourceAccountId;
            this.destinationAccountId = destinationAccountId;
            this.createdAt = createdAt;
        }
    }

    /**
     * Advance to {@code newStatus} only when it does not regress the rank. Returns {@code true} when
     * the status actually changed. Stale/out-of-order events (lower rank) are ignored.
     */
    public boolean advanceTo(TransferStatus newStatus, String failureReason, Instant now) {
        if (newStatus.rank() < this.statusRank) {
            return false;
        }
        this.status = newStatus.name();
        this.statusRank = newStatus.rank();
        this.failureReason = failureReason;
        this.updatedAt = now;
        return true;
    }

    public String transferId() {
        return transferId;
    }

    public String status() {
        return status;
    }

    public MoneyAmount amount() {
        return amount;
    }

    public String sourceAccountId() {
        return sourceAccountId;
    }

    public String destinationAccountId() {
        return destinationAccountId;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
