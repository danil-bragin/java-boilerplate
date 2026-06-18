package com.acme.bank.transfers.adapter.out.messaging;

import com.acme.bank.contracts.MoneyMapper;
import com.acme.bank.transfers.domain.PostingRequestedEvent;
import com.acme.bank.transfers.domain.TransferCompletedEvent;
import com.acme.bank.transfers.domain.TransferFailedEvent;

/** Maps clean domain events to their Avro integration contracts. */
public final class TransferAvroMapper {

    private TransferAvroMapper() {}

    public static com.acme.bank.contracts.avro.TransferRequested toAvro(
            com.acme.bank.transfers.domain.TransferRequested event) {
        return com.acme.bank.contracts.avro.TransferRequested.newBuilder()
                .setTransferId(event.transferId())
                .setSourceAccountId(event.sourceAccountId())
                .setDestinationAccountId(event.destinationAccountId())
                .setAmount(MoneyMapper.toAvro(event.amount()))
                .setRequestedBy(event.requestedBy())
                .setRequestedAt(java.time.Instant.now())
                .build();
    }

    public static com.acme.bank.contracts.avro.PostingRequested toAvro(PostingRequestedEvent event) {
        return com.acme.bank.contracts.avro.PostingRequested.newBuilder()
                .setTransferId(event.transferId())
                .setSourceAccountId(event.sourceAccountId())
                .setDestinationAccountId(event.destinationAccountId())
                .setAmount(MoneyMapper.toAvro(event.amount()))
                .build();
    }

    public static com.acme.bank.contracts.avro.TransferCompleted toAvro(TransferCompletedEvent event) {
        return com.acme.bank.contracts.avro.TransferCompleted.newBuilder()
                .setTransferId(event.transferId())
                .setPostingId(event.transferId())
                .setCompletedAt(java.time.Instant.now())
                .build();
    }

    public static com.acme.bank.contracts.avro.TransferFailed toAvro(TransferFailedEvent event) {
        return com.acme.bank.contracts.avro.TransferFailed.newBuilder()
                .setTransferId(event.transferId())
                .setReason(event.reason() != null ? event.reason() : "UNKNOWN")
                .build();
    }
}
