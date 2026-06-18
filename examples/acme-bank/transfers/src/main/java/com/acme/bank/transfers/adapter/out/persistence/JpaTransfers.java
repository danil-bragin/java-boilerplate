package com.acme.bank.transfers.adapter.out.persistence;

import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.TransferStatus;
import com.acme.bank.transfers.domain.Transfers;
import com.acme.money.Assets;
import com.acme.persistence.MoneyAmount;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
class JpaTransfers implements Transfers {

    private final TransferJpaRepository repository;

    JpaTransfers(TransferJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Transfer t) {
        // Touch updated_at on every save so the reconciler can age non-terminal transfers.
        repository.save(new TransferJpaEntity(
                t.id().value(),
                t.sourceAccountId(),
                t.destinationAccountId(),
                MoneyAmount.from(t.amount()),
                t.requestedBy(),
                t.status().name(),
                t.failureReason(),
                Instant.now()));
    }

    @Override
    public List<Transfer> findStuck(List<TransferStatus> statuses, Instant cutoff, int limit) {
        List<String> statusNames = statuses.stream().map(TransferStatus::name).toList();
        return repository.findStuck(statusNames, cutoff, PageRequest.of(0, limit)).stream()
                .map(this::rehydrate)
                .toList();
    }

    @Override
    public Optional<Transfer> findById(TransferId id) {
        return repository.findById(id.value()).map(e -> rehydrate(e));
    }

    @Override
    public boolean exists(TransferId id) {
        return repository.existsById(id.value());
    }

    @Override
    public List<Transfer> query(String accountId, TransferStatus status, int page, int size) {
        String statusName = status == null ? null : status.name();
        return repository.query(accountId, statusName, PageRequest.of(page, size)).stream()
                .map(this::rehydrate)
                .toList();
    }

    private Transfer rehydrate(TransferJpaEntity e) {
        return Transfer.rehydrate(
                new TransferId(e.getId()),
                e.getSourceAccountId(),
                e.getDestinationAccountId(),
                e.getAmount().toMoney(Assets::of),
                e.getRequestedBy(),
                TransferStatus.valueOf(e.getStatus()),
                e.getFailureReason(),
                e.getUpdatedAt());
    }
}
