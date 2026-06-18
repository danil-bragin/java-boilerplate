package com.acme.bank.gateway.web;

import com.acme.bank.gateway.api.TransfersApi;
import com.acme.bank.gateway.api.dto.CreateTransferRequest;
import com.acme.bank.gateway.api.dto.Money;
import com.acme.bank.gateway.api.dto.TransferAccepted;
import com.acme.bank.gateway.api.dto.TransferPage;
import com.acme.bank.gateway.api.dto.TransferView;
import com.acme.bank.gateway.application.GetTransfer;
import com.acme.bank.gateway.application.ListTransfers;
import com.acme.bank.gateway.application.SubmitTransfer;
import com.acme.persistence.AssetLookup;
import com.acme.web.error.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Edge controller implementing the generated {@link TransfersApi}. Because controllers implement the
 * generated interface, any contract operation without an implementation is a compile error — the
 * spec stays the source of truth.
 */
@RestController
public class TransferController implements TransfersApi {

    private final SubmitTransfer submitTransfer;
    private final GetTransfer getTransfer;
    private final ListTransfers listTransfers;
    private final AssetLookup assetLookup;

    public TransferController(
            SubmitTransfer submitTransfer,
            GetTransfer getTransfer,
            ListTransfers listTransfers,
            AssetLookup assetLookup) {
        this.submitTransfer = submitTransfer;
        this.getTransfer = getTransfer;
        this.listTransfers = listTransfers;
        this.assetLookup = assetLookup;
    }

    @Override
    public ResponseEntity<TransferAccepted> createTransfer(
            String idempotencyKey, CreateTransferRequest createTransferRequest) {
        String transferId = submitTransfer.submit(createTransferRequest, idempotencyKey);
        return ResponseEntity.accepted().body(new TransferAccepted(transferId, "REQUESTED"));
    }

    @Override
    public ResponseEntity<TransferView> getTransfer(String id) {
        return getTransfer
                .byId(id)
                .map(view -> ResponseEntity.ok(this.toDto(view)))
                .orElseThrow(() -> new ApiException(GatewayErrorCode.TRANSFER_NOT_FOUND, Map.of("transferId", id)));
    }

    @Override
    public ResponseEntity<TransferPage> listTransfers(String accountId, String status, Integer page, Integer size) {
        ListTransfers.Result result = listTransfers.list(accountId, status, page, size);
        List<TransferView> content = result.content().stream().map(this::toDto).toList();
        TransferPage body = new TransferPage()
                .content(content)
                .page(result.page())
                .size(result.size())
                .totalElements(result.totalElements());
        return ResponseEntity.ok(body);
    }

    private TransferView toDto(com.acme.bank.gateway.projection.TransferView view) {
        com.acme.money.Money money = view.amount().toMoney(assetLookup);
        String value = view.amount()
                .getAmount()
                .setScale(money.asset().scale(), java.math.RoundingMode.HALF_EVEN)
                .toPlainString();
        TransferView dto = new TransferView(
                view.transferId(),
                TransferView.StatusEnum.fromValue(view.status()),
                new Money(value, money.asset().code()),
                view.sourceAccountId(),
                view.destinationAccountId());
        dto.setFailureReason(view.failureReason());
        dto.setCreatedAt(OffsetDateTime.ofInstant(view.createdAt(), ZoneOffset.UTC));
        dto.setUpdatedAt(OffsetDateTime.ofInstant(view.updatedAt(), ZoneOffset.UTC));
        return dto;
    }
}
