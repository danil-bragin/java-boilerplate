package com.acme.bank.gateway.application;

import com.acme.bank.gateway.projection.TransferView;
import com.acme.bank.gateway.projection.TransferViewRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists transfers from the CQRS read model with simple filtering and paging. */
@Service
public class ListTransfers {

    private final TransferViewRepository repository;

    public ListTransfers(TransferViewRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Result list(String accountId, String status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        List<TransferView> content;
        if (accountId != null && !accountId.isBlank()) {
            content = repository.findBySourceAccountIdOrDestinationAccountId(accountId, accountId, pageable);
        } else if (status != null && !status.isBlank()) {
            content = repository.findByStatus(status, pageable);
        } else {
            content = repository.findAll(pageable).getContent();
        }
        return new Result(content, page, size, repository.count());
    }

    /** Page of read-model rows plus paging metadata. */
    public record Result(List<TransferView> content, int page, int size, long totalElements) {}
}
