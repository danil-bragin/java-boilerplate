package com.acme.bank.gateway.application;

import com.acme.bank.gateway.projection.TransferView;
import com.acme.bank.gateway.projection.TransferViewRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a single transfer from the CQRS read model. */
@Service
public class GetTransfer {

    private final TransferViewRepository repository;

    public GetTransfer(TransferViewRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<TransferView> byId(String transferId) {
        return repository.findById(transferId);
    }
}
