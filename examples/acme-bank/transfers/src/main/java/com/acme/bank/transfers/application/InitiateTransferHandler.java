package com.acme.bank.transfers.application;

import an.awesome.pipelinr.Command;
import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class InitiateTransferHandler implements Command.Handler<InitiateTransferCommand, String> {

    private final Transfers transfers;
    private final ApplicationEventPublisher events;

    public InitiateTransferHandler(Transfers transfers, ApplicationEventPublisher events) {
        this.transfers = transfers;
        this.events = events;
    }

    @Override
    public String handle(InitiateTransferCommand command) {
        TransferId id = new TransferId(command.transferId());
        if (transfers.exists(id)) {
            return id.value(); // idempotent initiate
        }
        Transfer transfer = Transfer.request(
                id, command.sourceAccountId(), command.destinationAccountId(), command.amount(), command.requestedBy());
        transfers.save(transfer);
        // Published inside the StronglyConsistent tx -> Modulith writes the outbox row, externalizes as Avro after
        // commit.
        events.publishEvent(transfer.toRequestedEvent());
        return id.value();
    }
}
