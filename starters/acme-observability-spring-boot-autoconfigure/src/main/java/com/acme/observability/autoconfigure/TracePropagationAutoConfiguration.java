package com.acme.observability.autoconfigure;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Guarantees a W3C {@code traceparent} OpenTelemetry {@link TextMapPropagator} is present so trace
 * context is injected into outbound carriers (HTTP headers, Kafka record headers). Without an
 * explicit propagator Boot can fall back to {@code TextMapPropagator.noop()} in some classpath
 * arrangements (e.g. when the bridge's micrometer {@code Propagator} is created before Boot's
 * propagation configuration), which silently drops {@code traceparent} — breaking cross-service
 * trace joins (including producer→Kafka→consumer).
 *
 * <p>Ordered before Boot's {@code OpenTelemetryTracingAutoConfiguration} so its
 * {@code @ConditionalOnMissingBean} noop propagator never wins.
 */
@AutoConfiguration(
        before = org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration.class)
@ConditionalOnClass({TextMapPropagator.class, W3CTraceContextPropagator.class})
public class TracePropagationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TextMapPropagator w3cTextMapPropagator() {
        return W3CTraceContextPropagator.getInstance();
    }
}
