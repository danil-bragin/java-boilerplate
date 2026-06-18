package com.acme.bank.transfers.application;

import com.acme.bank.transfers.adapter.out.reconcile.AccountsPostingClient;
import com.acme.bank.transfers.domain.PostingRequestedEvent;
import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferCompletedEvent;
import com.acme.bank.transfers.domain.TransferFailedEvent;
import com.acme.bank.transfers.domain.TransferRequested;
import com.acme.bank.transfers.domain.TransferStatus;
import com.acme.bank.transfers.domain.Transfers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money-safe stuck-saga reconciler. A ShedLock-guarded scheduled sweep finds non-terminal transfers
 * older than the configured thresholds and drives them to a terminal state without ever moving (or
 * un-moving) money:
 *
 * <ul>
 *   <li><b>Pre-money</b> (REQUESTED/APPROVED): re-emit the upstream event to re-drive (idempotent,
 *       inbox-deduped downstream); past {@code fail-after}, hard {@code timeOut()} → SAGA_TIMEOUT —
 *       always safe because no ledger posting can have happened yet.
 *   <li><b>POSTING</b> (the money state): ask accounts' ledger — the source of truth.
 *       <ul>
 *         <li>{@code posted=true} → {@code complete()} (recovers a lost {@code ledger-posted});
 *         <li>{@code posted=false} and past {@code fail-after} → {@code fail(SAGA_TIMEOUT)} (money
 *             confirmed not moved → safe to fail); otherwise re-emit {@code posting-requested};
 *         <li>transport error (empty) → SKIP this round. A POSTING transfer is NEVER failed on the
 *             absence of an answer — only on a confirmed {@code false}. This is the money-safety
 *             invariant that preserves the BANK-11 strong-consistency guarantee end-to-end.
 *       </ul>
 * </ul>
 *
 * <p>Re-publishing a domain event creates a fresh outbox publication → a new Kafka message; every
 * consumer is inbox-deduped and the posting is PK-anchored, so re-emitting is safe.
 */
@Component
@ConditionalOnProperty(prefix = "acme.bank.reconciler", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ReconcilerProperties.class)
public class SagaReconciler {

    private static final Logger log = LoggerFactory.getLogger(SagaReconciler.class);

    private static final List<TransferStatus> NON_TERMINAL =
            List.of(TransferStatus.REQUESTED, TransferStatus.APPROVED, TransferStatus.POSTING);

    private final Transfers transfers;
    private final AccountsPostingClient accountsPostingClient;
    private final ApplicationEventPublisher events;
    private final ReconcilerProperties props;
    private final ObjectProvider<SagaReconciler> self;

    public SagaReconciler(
            Transfers transfers,
            AccountsPostingClient accountsPostingClient,
            ApplicationEventPublisher events,
            ReconcilerProperties props,
            ObjectProvider<SagaReconciler> self) {
        this.transfers = transfers;
        this.accountsPostingClient = accountsPostingClient;
        this.events = events;
        this.props = props;
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${acme.bank.reconciler.fixed-delay:PT15S}")
    @SchedulerLock(name = "transfer-saga-reconciler", lockAtMostFor = "PT2M")
    public void reconcile() {
        Instant nudgeCutoff = Instant.now().minus(props.getNudgeAfter());
        List<Transfer> stuck = transfers.findStuck(NON_TERMINAL, nudgeCutoff, props.getBatchSize());
        if (stuck.isEmpty()) {
            return;
        }
        log.info("saga reconciler sweeping {} stuck transfer(s)", stuck.size());
        for (Transfer t : stuck) {
            try {
                // Each transfer in its own tx (REQUIRES_NEW) so one bad row doesn't abort the batch
                // and each save+publish is atomic. Go through the proxy so @Transactional applies.
                self.getObject().reconcileOne(t);
            } catch (RuntimeException ex) {
                log.warn("saga reconciler failed for transfer {}: {}", t.id().value(), ex.toString());
            }
        }
    }

    /**
     * Reconcile a single stuck transfer. Package-visible so tests can drive it directly. Runs in its
     * own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void reconcileOne(Transfer t) {
        boolean pastFail = isOlderThan(t, props.getFailAfter());
        String id = t.id().value();

        if (t.status() == TransferStatus.POSTING) {
            Optional<Boolean> posted = accountsPostingClient.posted(id);
            if (posted.isEmpty()) {
                // Transport error — never fail money on a failed query. Skip this round.
                log.info("reconciler: accounts unreachable for POSTING transfer {} — skipping round", id);
                return;
            }
            if (posted.get()) {
                // Ledger confirms money moved: complete (recovers a lost ledger-posted event).
                t.complete();
                transfers.save(t);
                events.publishEvent(new TransferCompletedEvent(id));
                log.info("reconciler: POSTING transfer {} confirmed posted — completed", id);
            } else if (pastFail) {
                // Money confirmed NOT moved and past the hard timeout: safe to fail.
                t.fail("SAGA_TIMEOUT");
                transfers.save(t);
                events.publishEvent(new TransferFailedEvent(id, "SAGA_TIMEOUT"));
                log.warn("reconciler: POSTING transfer {} confirmed not posted past fail-after — SAGA_TIMEOUT", id);
            } else {
                // Not yet at the hard timeout: re-drive the posting (idempotent at accounts).
                events.publishEvent(
                        new PostingRequestedEvent(id, t.sourceAccountId(), t.destinationAccountId(), t.amount()));
                log.info("reconciler: re-emitted posting-requested for stuck POSTING transfer {}", id);
            }
            return;
        }

        // Pre-money (REQUESTED / APPROVED): always money-safe.
        if (pastFail) {
            t.timeOut(); // → FAILED(SAGA_TIMEOUT)
            transfers.save(t);
            events.publishEvent(new TransferFailedEvent(id, "SAGA_TIMEOUT"));
            log.warn("reconciler: pre-money transfer {} past fail-after — SAGA_TIMEOUT", id);
        } else if (t.status() == TransferStatus.REQUESTED) {
            events.publishEvent(new TransferRequested(
                    id, t.sourceAccountId(), t.destinationAccountId(), t.amount(), t.requestedBy()));
            log.info("reconciler: re-emitted transfer-requested for stuck REQUESTED transfer {}", id);
        } else { // APPROVED
            events.publishEvent(
                    new PostingRequestedEvent(id, t.sourceAccountId(), t.destinationAccountId(), t.amount()));
            log.info("reconciler: re-emitted posting-requested for stuck APPROVED transfer {}", id);
        }
    }

    private boolean isOlderThan(Transfer t, Duration age) {
        Instant updatedAt = t.updatedAt();
        // A rehydrated stuck transfer always has updatedAt; if absent, treat as not-yet-aged.
        return updatedAt != null && updatedAt.isBefore(Instant.now().minus(age));
    }
}
