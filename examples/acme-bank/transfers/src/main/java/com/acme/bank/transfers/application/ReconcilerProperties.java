package com.acme.bank.transfers.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Thresholds for the money-safe stuck-saga reconciler ({@code acme.bank.reconciler.*}). */
@ConfigurationProperties(prefix = "acme.bank.reconciler")
public class ReconcilerProperties {

    /** Whether the scheduled sweep runs. */
    private boolean enabled = true;

    /** Age after which a stuck transfer is nudged (re-emit to re-drive). */
    private Duration nudgeAfter = Duration.ofSeconds(30);

    /** Age after which a pre-money stuck transfer is hard-failed (SAGA_TIMEOUT). POSTING is never failed. */
    private Duration failAfter = Duration.ofMinutes(5);

    /**
     * Age after which a still-unposted POSTING transfer is treated as genuinely stuck: the reconciler
     * emits the {@code acme.saga.stuck} metric + a WARN ("page a human") but never changes state. Should
     * be ≥ {@link #failAfter}.
     */
    private Duration stuckAfter = Duration.ofMinutes(15);

    /** Max transfers reconciled per sweep. */
    private int batchSize = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getNudgeAfter() {
        return nudgeAfter;
    }

    public void setNudgeAfter(Duration nudgeAfter) {
        this.nudgeAfter = nudgeAfter;
    }

    public Duration getFailAfter() {
        return failAfter;
    }

    public void setFailAfter(Duration failAfter) {
        this.failAfter = failAfter;
    }

    public Duration getStuckAfter() {
        return stuckAfter;
    }

    public void setStuckAfter(Duration stuckAfter) {
        this.stuckAfter = stuckAfter;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
