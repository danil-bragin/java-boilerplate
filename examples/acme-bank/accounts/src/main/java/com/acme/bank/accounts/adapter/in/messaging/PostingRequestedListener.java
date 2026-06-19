package com.acme.bank.accounts.adapter.in.messaging;

import an.awesome.pipelinr.Pipeline;
import com.acme.bank.accounts.application.PostTransferCommand;
import com.acme.bank.accounts.application.PostTransferResult;
import com.acme.bank.accounts.domain.LedgerPostedEvent;
import com.acme.bank.accounts.domain.PostingRejectedEvent;
import com.acme.bank.contracts.MoneyMapper;
import com.acme.messaging.Inbox;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes Avro {@code PostingRequested} from topic {@code posting-requested}, deduplicates via Inbox,
 * posts the transfer via the domain pipeline, and emits a {@code LedgerPostedEvent} or
 * {@code PostingRejectedEvent} depending on the outcome.
 *
 * <p>BANK-21: a BATCH listener (see {@code BatchListenerConfig}) — the MONEY path, batched safely. The
 * whole poll runs in ONE transaction: this method is {@code @Transactional} and each per-record {@code
 * pipeline.send(PostTransferCommand)} (a {@code StronglyConsistent} command whose {@code TransactionMiddleware}
 * uses a default-propagation {@code TransactionTemplate}) JOINS that one transaction (PROPAGATION_REQUIRED).
 *
 * <p><b>Overdraft is impossible — STRICTER than per-record.</b> {@code posting-requested} is keyed by the
 * SOURCE account (BANK-15), so a source's postings co-partition into ONE batch and this one tx. Each
 * posting takes the BANK-11 {@code findByIdForUpdate} source lock (re-entrant within the tx) and reads its
 * derived balance with {@code SELECT SUM(...)}; since the EARLIER postings in this batch already wrote their
 * ledger entries in this (uncommitted) tx, that SUM sees them and the postings serialize — no overdraft.
 *
 * <p><b>A rejection is a result, NOT a rollback.</b> INSUFFICIENT_FUNDS / ACCOUNT_NOT_OPERATIONAL /
 * ACCOUNT_ASSET_MISMATCH come back as {@code PostTransferResult.rejected(..)} (the handler returns, never
 * throws), so we emit {@code posting-rejected} and CONTINUE — a rejection never rolls back its successful
 * siblings in the batch. Per-record {@code inbox.firstTime("accounts", transferId)} dedup is kept inside the
 * loop (and the posting-PK anchor in the handler flushes per posting), so a redelivered duplicate — within
 * or across batches — short-circuits and never double-posts.
 *
 * <p><b>Only a true infra error fails the batch.</b> If {@code pipeline.send} throws (DB down, not a
 * business rejection), we wrap it in {@code BatchListenerFailedException(record, i)} so the container commits
 * offsets up to i and re-drives from i; the posting-PK anchor makes that re-drive idempotent, and any
 * posting a rolled-back partial commit leaves undone is also re-driven by the transfers {@code
 * SagaReconciler} against the ledger. So no money guarantee depends on the batch commit boundary.
 */
@Component
public class PostingRequestedListener {

    private final Inbox inbox;
    private final Pipeline pipeline;
    private final ApplicationEventPublisher eventPublisher;

    public PostingRequestedListener(Inbox inbox, Pipeline pipeline, ApplicationEventPublisher eventPublisher) {
        this.inbox = inbox;
        this.pipeline = pipeline;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(
            topics = "posting-requested",
            groupId = "accounts",
            containerFactory = "batchListenerContainerFactory")
    @Transactional
    public void onPostingRequested(
            List<ConsumerRecord<String, com.acme.bank.contracts.avro.PostingRequested>> records) {
        for (int i = 0; i < records.size(); i++) {
            com.acme.bank.contracts.avro.PostingRequested event = records.get(i).value();
            String transferId = event.getTransferId().toString();
            if (!inbox.firstTime("accounts", transferId)) {
                continue;
            }
            PostTransferResult result;
            try {
                result = pipeline.send(new PostTransferCommand(
                        transferId,
                        event.getSourceAccountId().toString(),
                        event.getDestinationAccountId().toString(),
                        MoneyMapper.fromAvro(event.getAmount())));
            } catch (RuntimeException e) {
                // TRUE infra error (not a business rejection — those are returned, not thrown). Surface this
                // record so the container commits offsets up to i and re-drives from i; the posting-PK anchor
                // makes the re-drive idempotent (an already-posted transfer short-circuits). The whole batch
                // tx rolls back, so any earlier posting in it is undone and will be re-driven (idempotently).
                throw new BatchListenerFailedException("posting-requested record failed: " + transferId, e, i);
            }

            // A business rejection is a NORMAL outcome — emit posting-rejected and CONTINUE; it does NOT roll
            // back the batch's already-posted siblings.
            if (result.posted()) {
                eventPublisher.publishEvent(new LedgerPostedEvent(transferId));
            } else {
                eventPublisher.publishEvent(new PostingRejectedEvent(transferId, result.reason()));
            }
        }
    }
}
