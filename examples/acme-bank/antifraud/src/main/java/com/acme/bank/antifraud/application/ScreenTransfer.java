package com.acme.bank.antifraud.application;

import com.acme.bank.antifraud.domain.RiskDecision;
import com.acme.bank.antifraud.domain.RiskEngine;
import com.acme.bank.antifraud.domain.TransferScreened;
import com.acme.money.Money;
import org.springframework.stereotype.Service;

/** Application use case: screen a transfer via the RiskEngine, persist the decision. */
@Service
public class ScreenTransfer {

    private final RiskEngine riskEngine;
    private final ScreeningStore store;

    public ScreenTransfer(RiskEngine riskEngine, ScreeningStore store) {
        this.riskEngine = riskEngine;
        this.store = store;
    }

    public TransferScreened screen(String transferId, String sourceAccountId, Money amount) {
        int velocity = store.velocity(sourceAccountId);
        RiskDecision decision = riskEngine.assess(amount, velocity);
        if (!store.existsByTransferId(transferId)) {
            store.save(transferId, sourceAccountId, decision.approved(), decision.reason());
        }
        return new TransferScreened(transferId, decision.approved(), decision.reason());
    }
}
