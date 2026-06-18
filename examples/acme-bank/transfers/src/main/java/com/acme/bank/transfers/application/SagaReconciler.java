package com.acme.bank.transfers.application;

import com.acme.bank.transfers.adapter.out.reconcile.AccountsPostingClient;
import com.acme.bank.transfers.domain.PostingRequestedEvent;
import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferCompletedEvent;
import com.acme.bank.transfers.domain.TransferFailedEvent;
import com.acme.bank.transfers.domain.TransferRequested;
import com.acme.bank.transfers.domain.TransferStatus;
import com.acme.bank.transfers.domain.Transfers;
import io.micrometer.core.instrument.MeterRegistry;
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
 * older than the configured thresholds and drives them toward a terminal state without ever moving
 * (or un-moving) money — and, critically, without ever <em>fabricating</em> a terminal over money it
 * cannot prove never moved:
 *
 * <ul>
 *   <li><b>Pre-money</b> (REQUESTED/APPROVED): re-emit the upstream event to re-drive (idempotent,
 *       inbox-deduped downstream); past {@code fail-after}, hard {@code timeOut()} → SAGA_TIMEOUT.
 *       This is the ONLY auto-FAILED path — always safe because no ledger posting can have happened
 *       before {@code POSTING}.
 *   <li><b>POSTING</b> (the money state): a POSTING transfer is reconciled against accounts' ledger
 *       but is <b>NEVER auto-failed</b> by the reconciler. Only the money authority (accounts)
 *       terminalizes a posting: {@code ledger-posted} → COMPLETED or accounts' own
 *       {@code posting-rejected} → FAILED (the {@code PostingResultListener} path).
 *       <ul>
 *         <li>{@code posted=true} → {@code complete()} (recovers a lost {@code ledger-posted}); safe
 *             because money provably moved.
 *         <li>{@code posted=false} → <b>re-drive only</b>: re-emit {@code posting-requested}
 *             (idempotent — accounts' inbox dedups; if it already posted, the next reconcile sees
 *             {@code posted=true} and completes). Do NOT fail, regardless of age. A
 *             {@code posted=false} answer is a POINT-IN-TIME snapshot ("not posted YET"), not a proof
 *             that money never moved: a still-in-flight {@code posting-requested} (unconsumed inbox /
 *             retry backoff / in {@code posting-requested-dlt} awaiting replay) could post AFTER the
 *             reconciler ran. Failing here would risk source-debited + dest-credited on a transfer
 *             the system already declared FAILED.
 *         <li>{@code posted=false} and past {@code stuck-after} → emit the {@code acme.saga.stuck}
 *             metric + a WARN identifying the transferId ("page a human" — money genuinely stuck,
 *             accounts permanently unreachable). State stays {@code POSTING}; re-drive continues.
 *         <li>transport error (empty) → SKIP this round, no state change.
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

    /** Counter incremented when a POSTING transfer is genuinely stuck (past {@code stuck-after}). */
    static final String STUCK_COUNTER = "acme.saga.stuck";

    private static final List<TransferStatus> NON_TERMINAL =
            List.of(TransferStatus.REQUESTED, TransferStatus.APPROVED, TransferStatus.POSTING);

    private final Transfers transfers;
    private final AccountsPostingClient accountsPostingClient;
    private final ApplicationEventPublisher events;
    private final ReconcilerProperties props;
    private final ObjectProvider<SagaReconciler> self;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public SagaReconciler(
            Transfers transfers,
            AccountsPostingClient accountsPostingClient,
            ApplicationEventPublisher events,
            ReconcilerProperties props,
            ObjectProvider<SagaReconciler> self,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.transfers = transfers;
        this.accountsPostingClient = accountsPostingClient;
        this.events = events;
        this.props = props;
        this.self = self;
        this.meterRegistry = meterRegistry;
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
                // Transport error — never act on the absence of an answer. Skip this round.
                log.info("reconciler: accounts unreachable for POSTING transfer {} — skipping round", id);
                return;
            }
            if (posted.get()) {
                // Ledger confirms money moved: complete (recovers a lost ledger-posted event). This is
                // the only terminal the reconciler may apply to a POSTING transfer — money provably moved.
                t.complete();
                transfers.save(t);
                events.publishEvent(new TransferCompletedEvent(id));
                log.info("reconciler: POSTING transfer {} confirmed posted — completed", id);
                return;
            }

            // posted=false: a POINT-IN-TIME snapshot ("not posted YET"), NOT a proof money never moved.
            // A still-in-flight posting-requested (unconsumed inbox / retry backoff / awaiting DLT replay)
            // could post AFTER us; accounts has no fence/cancellation. So we NEVER fail — only re-drive.
            // Re-emit is idempotent: accounts' inbox dedups, and if it already posted, the next reconcile
            // sees posted=true and completes. A POSTING transfer reaches terminal ONLY via accounts'
            // authority (ledger-posted → COMPLETED / posting-rejected → FAILED).
            events.publishEvent(
                    new PostingRequestedEvent(id, t.sourceAccountId(), t.destinationAccountId(), t.amount()));
            log.info("reconciler: re-emitted posting-requested for stuck POSTING transfer {}", id);

            if (isOlderThan(t, props.getStuckAfter())) {
                // Genuinely stuck (accounts permanently unreachable / posting permanently in-flight):
                // surface for manual resolution. State stays POSTING — only accounts may terminalize it.
                MeterRegistry registry = meterRegistry.getIfAvailable();
                if (registry != null) {
                    registry.counter(STUCK_COUNTER, "status", t.status().name()).increment();
                }
                log.warn(
                        "reconciler: POSTING transfer {} stuck past stuck-after (not posted, accounts not "
                                + "terminalizing) — emitted {}, NOT failing; manual resolution required",
                        id,
                        STUCK_COUNTER);
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
