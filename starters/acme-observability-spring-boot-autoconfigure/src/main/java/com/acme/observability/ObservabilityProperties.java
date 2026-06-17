package com.acme.observability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated configuration for the observability starter. Fails fast on invalid values. */
@Validated
@ConfigurationProperties(prefix = "acme.observability")
public class ObservabilityProperties {

    @NotNull
    @Valid
    private final SchedulerLock schedulerLock = new SchedulerLock();

    public SchedulerLock getSchedulerLock() {
        return schedulerLock;
    }

    /** ShedLock defaults. */
    public static class SchedulerLock {

        /** Upper bound a lock is held if the holding node dies mid-job. Must be at least 1 second. */
        @NotNull
        @DurationMin(seconds = 1)
        private Duration defaultLockAtMostFor = Duration.ofMinutes(10);

        public Duration getDefaultLockAtMostFor() {
            return defaultLockAtMostFor;
        }

        public void setDefaultLockAtMostFor(Duration defaultLockAtMostFor) {
            this.defaultLockAtMostFor = defaultLockAtMostFor;
        }
    }
}
