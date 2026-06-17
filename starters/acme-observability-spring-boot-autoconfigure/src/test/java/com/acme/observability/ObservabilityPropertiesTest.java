package com.acme.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ObservabilityPropertiesTest {

    @Test
    void defaultsAreSensible() {
        ObservabilityProperties props = new ObservabilityProperties();
        assertThat(props.getSchedulerLock().getDefaultLockAtMostFor()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void valuesBind() {
        ObservabilityProperties props = new ObservabilityProperties();
        props.getSchedulerLock().setDefaultLockAtMostFor(Duration.ofMinutes(2));
        assertThat(props.getSchedulerLock().getDefaultLockAtMostFor()).isEqualTo(Duration.ofMinutes(2));
    }
}
