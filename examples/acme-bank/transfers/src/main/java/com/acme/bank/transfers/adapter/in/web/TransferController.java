package com.acme.bank.transfers.adapter.in.web;

import an.awesome.pipelinr.Pipeline;
import com.acme.bank.transfers.application.InitiateTransferCommand;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.web.error.ApiException;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transfers")
public class TransferController {

    private final Pipeline pipeline;
    private final Transfers transfers;

    public TransferController(Pipeline pipeline, Transfers transfers) {
        this.pipeline = pipeline;
        this.transfers = transfers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> initiate(@Valid @RequestBody CreateTransferRequest request) {
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of(request.amount(), Assets.of(request.asset()));
        pipeline.send(new InitiateTransferCommand(
                transferId, request.sourceAccountId(), request.destinationAccountId(), amount, "api"));
        return Map.of("transferId", transferId, "status", "REQUESTED");
    }

    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable String id) {
        var transfer = transfers
                .findById(new TransferId(id))
                .orElseThrow(() -> new ApiException(TransferErrorCode.TRANSFER_NOT_FOUND, Map.of("transferId", id)));
        return Map.of(
                "transferId", transfer.id().value(), "status", transfer.status().name());
    }
}
