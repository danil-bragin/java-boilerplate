package com.acme.bank.antifraud.adapter.out.messaging;

/** Maps the domain {@code TransferScreened} event to its Avro integration contract. */
public final class ScreeningAvroMapper {

    private ScreeningAvroMapper() {}

    public static com.acme.bank.contracts.avro.TransferScreened toAvro(
            com.acme.bank.antifraud.domain.TransferScreened event) {
        return com.acme.bank.contracts.avro.TransferScreened.newBuilder()
                .setTransferId(event.transferId())
                .setApproved(event.approved())
                .setReason(event.reason())
                .build();
    }
}
