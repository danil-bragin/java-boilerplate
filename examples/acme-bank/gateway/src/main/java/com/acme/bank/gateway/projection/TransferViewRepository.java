package com.acme.bank.gateway.projection;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository over the {@link TransferView} read model. */
public interface TransferViewRepository extends JpaRepository<TransferView, String> {

    List<TransferView> findBySourceAccountIdOrDestinationAccountId(
            String sourceAccountId, String destinationAccountId, Pageable pageable);

    List<TransferView> findByStatus(String status, Pageable pageable);
}
