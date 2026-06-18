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
