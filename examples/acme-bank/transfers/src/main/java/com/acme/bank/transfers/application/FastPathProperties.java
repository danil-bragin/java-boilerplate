package com.acme.bank.transfers.application;

import com.acme.money.Asset;
import com.acme.money.Money;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fast-path eligibility policy. A transfer is fast-path-eligible when the flag is on AND its amount is
 * {@code <= maxAmount} in the SAME asset (a small, auto-approvable transfer). The threshold is set
 * conservatively BELOW the antifraud amount-limit (10000 USD) so an eligible transfer is one antifraud
 * would auto-approve anyway — the fast-path may safely skip the screening hop.
 */
@ConfigurationProperties(prefix = "acme.bank.fast-path")
public class FastPathProperties {

    /** Master switch. When false, every transfer takes the unchanged async slow-path. */
    private boolean enabled = true;

    /** Inclusive amount ceiling (exact decimal string, e.g. "1000.00"). */
    private String maxAmount = "1000.00";

    /** Asset the threshold is denominated in. Cross-asset transfers are never fast-path-eligible. */
    private String maxAmountAsset = "USD";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMaxAmount() {
        return maxAmount;
    }

    public void setMaxAmount(String maxAmount) {
        this.maxAmount = maxAmount;
    }

    public String getMaxAmountAsset() {
        return maxAmountAsset;
    }

    public void setMaxAmountAsset(String maxAmountAsset) {
        this.maxAmountAsset = maxAmountAsset;
    }

    /**
     * Eligible iff the flag is on, the transfer asset matches the threshold asset, and the amount is at
     * or below the threshold. Cross-asset transfers are conservatively ineligible (slow-path).
     */
    public boolean isEligible(Money amount) {
        if (!enabled) {
            return false;
        }
        Asset thresholdAsset = new Asset(maxAmountAsset, amount.asset().scale());
        if (!amount.asset().code().equals(thresholdAsset.code())) {
            return false;
        }
        Money ceiling = Money.of(new BigDecimal(maxAmount).toPlainString(), amount.asset());
        return amount.compareTo(ceiling) <= 0;
    }
}
