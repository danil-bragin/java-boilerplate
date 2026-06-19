package com.acme.bank.accounts.adapter.in.messaging;

import an.awesome.pipelinr.Pipeline;
import com.acme.bank.accounts.application.PostTransferCommand;
import com.acme.bank.accounts.application.PostTransferResult;
import com.acme.bank.accounts.domain.LedgerPostedEvent;
import com.acme.bank.accounts.domain.PostingRejectedEvent;
import com.acme.bank.contracts.MoneyMapper;
import com.acme.messaging.Inbox;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes Avro {@code PostingRequested} from topic {@code posting-requested}, deduplicates via Inbox,
 * posts the transfer via the domain pipeline, and emits a {@code LedgerPostedEvent} or
 * {@code PostingRejectedEvent} depending on the outcome.
 *
 * <p>The MONEY path is processed PER RECORD: one Kafka record = one {@code @Transactional} invocation =
 * its own DB transaction and its own committed offset. This is the money-safe shape. (BANK-21 batched this
 * in one tx with {@code BatchListenerFailedException}, but on a non-transactional container that could
 * commit offsets for records whose DB tx was rolled back by a mid-batch infra error — silently losing money
 * movements. Reverted: per-record gives each posting its own tx+offset, so a failure rolls back AND re-drives
 * exactly that record; there is no batch commit boundary across which money can be lost. The fsync cost is
 * amortized at the Postgres level by BANK-19b group-commit, not by an application batch.)
 *
 * <p><b>Overdraft is impossible.</b> {@code inbox.firstTime("accounts", transferId)} dedups redeliveries
 * (with the posting-PK anchor in the handler) so a transfer never double-posts. Each posting takes the
 * BANK-11 {@code findByIdForUpdate} source lock and reads its derived balance with {@code SELECT SUM(...)};
 * concurrent same-source postings serialize on that row lock, so the balance can never go negative. A
 * business rejection (INSUFFICIENT_FUNDS / ACCOUNT_NOT_OPERATIONAL / ACCOUNT_ASSET_MISMATCH) comes back as
 * {@code PostTransferResult.rejected(..)} (the handler returns, never throws) and is emitted as {@code
 * posting-rejected}; only a true infra error throws and rolls back this single record's tx (re-driven
 * idempotently).
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

    @KafkaListener(topics = "posting-requested", groupId = "accounts")
    @Transactional
    public void onPostingRequested(com.acme.bank.contracts.avro.PostingRequested event) {
        String transferId = event.getTransferId().toString();
        if (!inbox.firstTime("accounts", transferId)) {
            return;
        }
        PostTransferResult result = pipeline.send(new PostTransferCommand(
                transferId,
                event.getSourceAccountId().toString(),
                event.getDestinationAccountId().toString(),
                MoneyMapper.fromAvro(event.getAmount())));

        if (result.posted()) {
            eventPublisher.publishEvent(new LedgerPostedEvent(transferId));
        } else {
            eventPublisher.publishEvent(new PostingRejectedEvent(transferId, result.reason()));
        }
    }
}
