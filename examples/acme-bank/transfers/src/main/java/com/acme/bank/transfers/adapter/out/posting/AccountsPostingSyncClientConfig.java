package com.acme.bank.transfers.adapter.out.posting;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} the fast-path uses to POST to the accounts money authority
 * ({@code POST /internal/postings}). The endpoint is network-internal and permits no bearer, so no
 * token relay is needed.
 *
 * <p>Connect/read timeouts are TIGHT and bounded (BANK-22 Fix 3): the sync call runs inside the
 * transfers {@code StronglyConsistent} DB tx and holds a Hikari connection for its whole duration, so a
 * slow accounts must not hold the connection long. A read timeout surfaces as
 * {@code ResourceAccessException} → the sync client's UNKNOWN path → the transfer is left {@code POSTING}
 * for the reconciler (never a guessed terminal); persistent slowness trips the breaker → async fallback.
 */
@Configuration
public class AccountsPostingSyncClientConfig {

    @ConfigurationProperties(prefix = "acme.bank.fast-path.accounts")
    public static class AccountsSyncClientProperties {
        /** Base URL of the accounts service (its {@code /internal/**} surface). */
        private String baseUrl = "http://localhost:8083";

        private Duration connectTimeout = Duration.ofMillis(200);
        private Duration readTimeout = Duration.ofMillis(500);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

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
    }

    @Bean
    AccountsSyncClientProperties accountsPostingSyncClientProperties() {
        return new AccountsSyncClientProperties();
    }

    @Bean
    RestClient accountsPostingSyncRestClient(RestClient.Builder builder, AccountsSyncClientProperties props) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(props.getConnectTimeout())
                .withReadTimeout(props.getReadTimeout());
        return builder.baseUrl(props.getBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
