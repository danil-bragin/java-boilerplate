package com.acme.bank.transfers.adapter.in.web;

import an.awesome.pipelinr.Pipeline;
import com.acme.bank.transfers.application.GetTransfer;
import com.acme.bank.transfers.application.InitiateTransferCommand;
import com.acme.bank.transfers.application.InitiateTransferResult;
import com.acme.bank.transfers.application.ListTransfers;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.TransferStatus;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.web.error.ApiException;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transfers")
public class TransferController {

    private final Pipeline pipeline;
    private final GetTransfer getTransfer;
    private final ListTransfers listTransfers;

    public TransferController(Pipeline pipeline, GetTransfer getTransfer, ListTransfers listTransfers) {
        this.pipeline = pipeline;
        this.getTransfer = getTransfer;
        this.listTransfers = listTransfers;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> initiate(@Valid @RequestBody CreateTransferRequest request) {
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of(request.amount(), Assets.of(request.asset()));
        InitiateTransferResult result = pipeline.send(new InitiateTransferCommand(
                transferId, request.sourceAccountId(), request.destinationAccountId(), amount, "api"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferId", result.transferId());
        body.put("status", result.status().name());
        if (result.failureReason() != null) {
            body.put("failureReason", result.failureReason());
        }
        // Fast-path terminal (COMPLETED/FAILED) → 200; slow-path / UNKNOWN (REQUESTED/POSTING) → 202.
        HttpStatus code = result.terminal() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(code).body(body);
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) TransferStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // Clamp paging to bound the PageRequest (DoS guard against e.g. size=100000000).
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        List<TransferView> views = listTransfers.handle(accountId, status, safePage, safeSize).stream()
                .map(TransferView::of)
                .toList();
        return Map.of("transfers", views, "page", safePage, "size", safeSize);
    }

    @GetMapping("/{id}")
    public TransferView get(@PathVariable String id) {
        return getTransfer
                .handle(new TransferId(id))
                .map(TransferView::of)
                .orElseThrow(() -> new ApiException(TransferErrorCode.TRANSFER_NOT_FOUND, Map.of("transferId", id)));
    }
}
