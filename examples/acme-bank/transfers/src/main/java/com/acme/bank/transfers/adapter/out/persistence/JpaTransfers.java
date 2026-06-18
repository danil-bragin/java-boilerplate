package com.acme.bank.transfers.adapter.out.persistence;

import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import com.acme.money.Assets;
import com.acme.persistence.MoneyAmount;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class JpaTransfers implements Transfers {

    private final TransferJpaRepository repository;

    JpaTransfers(TransferJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Transfer t) {
        repository.save(new TransferJpaEntity(
                t.id().value(),
                t.sourceAccountId(),
                t.destinationAccountId(),
                MoneyAmount.from(t.amount()),
                t.requestedBy(),
                t.status().name(),
                t.failureReason()));
    }

    @Override
    public Optional<Transfer> findById(TransferId id) {
        return repository.findById(id.value()).map(e -> rehydrate(e));
    }

    @Override
    public boolean exists(TransferId id) {
        return repository.existsById(id.value());
    }

    private Transfer rehydrate(TransferJpaEntity e) {
        // For BANK-2 only the REQUESTED path is exercised; rehydration of later states is added in BANK-5.
        Transfer t = Transfer.request(
                new TransferId(e.getId()),
                e.getSourceAccountId(),
                e.getDestinationAccountId(),
                e.getAmount().toMoney(Assets::of),
                e.getRequestedBy());
        return t;
    }
}
