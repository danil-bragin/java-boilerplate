package com.acme.httpclient;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the declarative HTTP client starter. Everything has a sane default and is
 * overridable under {@code acme.httpclient.*}.
 */
@ConfigurationProperties(prefix = "acme.httpclient")
public class HttpClientProperties {

    /** Connect timeout applied to the shared {@code RestClient.Builder}. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Read (response) timeout applied to the shared {@code RestClient.Builder}. */
    private Duration readTimeout = Duration.ofSeconds(10);

    private final TokenRelay tokenRelay = new TokenRelay();

    private final Resilience resilience = new Resilience();

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public TokenRelay getTokenRelay() {
        return tokenRelay;
    }

    public Resilience getResilience() {
        return resilience;
    }

    /** OAuth2 bearer token relay onto outbound calls. */
    public static class TokenRelay {
        /**
         * When {@code true} (and the resource-server JWT types are on the classpath) the current
         * request's {@code Authorization: Bearer ...} is copied onto every outbound call.
         */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Defaults for the Resilience4j decorator (instance presets live under {@code resilience4j.*}). */
    public static class Resilience {
        /**
         * Resilience4j instance name used by {@code ResilienceDecorator.call(Supplier)} when no
         * explicit name is given — resolves the {@code resilience4j.circuitbreaker/retry.instances.<name>}
         * presets brought by {@code acme-resilience}.
         */
        private String defaultInstance = "httpclient";

        public String getDefaultInstance() {
            return defaultInstance;
        }

        public void setDefaultInstance(String defaultInstance) {
            this.defaultInstance = defaultInstance;
        }
    }
}
