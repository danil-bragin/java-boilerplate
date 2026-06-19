package com.acme.bank.transfers.application;

import com.acme.money.Asset;
import com.acme.money.Money;
import java.math.BigDecimal;
import java.time.Duration;
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

    /**
     * BANK-22 Fix 1: max per-source velocity that may still take the fast-path. The fast-path skips
     * antifraud screening, so it records no screening_decision → the antifraud VELOCITY rule is blind to
     * fast-pathed transfers. To stop an unbounded N × ≤threshold drain from one source, transfers counts
     * the source's recent transfers in its OWN DB; once the count reaches this cap the transfer is NO
     * LONGER fast-path-eligible and is routed to the async slow-path (full antifraud screening, which DOES
     * record + enforce velocity). Default 5 — aligned to the antifraud {@code maxVelocity}.
     */
    private int maxVelocityPerSource = 5;

    /** Sliding window over which the per-source velocity is counted (created_at &gt; now() - window). */
    private Duration velocityWindow = Duration.ofMinutes(1);

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

    public int getMaxVelocityPerSource() {
        return maxVelocityPerSource;
    }

    public void setMaxVelocityPerSource(int maxVelocityPerSource) {
        this.maxVelocityPerSource = maxVelocityPerSource;
    }

    public Duration getVelocityWindow() {
        return velocityWindow;
    }

    public void setVelocityWindow(Duration velocityWindow) {
        this.velocityWindow = velocityWindow;
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
