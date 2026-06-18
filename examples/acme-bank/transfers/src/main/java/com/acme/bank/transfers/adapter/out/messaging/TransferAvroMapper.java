package com.acme.bank.transfers.adapter.out.messaging;

import com.acme.bank.contracts.MoneyMapper;

/** Maps the clean domain {@code TransferRequested} event to its Avro integration contract. */
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
}
