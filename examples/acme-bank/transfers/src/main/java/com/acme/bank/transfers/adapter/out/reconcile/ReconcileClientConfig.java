package com.acme.bank.transfers.adapter.out.reconcile;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} the saga reconciler uses to query the accounts money-truth endpoint
 * ({@code /internal/postings/{id}}). The endpoint is network-internal and permits no bearer, so no
 * token relay is needed.
 */
@Configuration
public class ReconcileClientConfig {

    @ConfigurationProperties(prefix = "acme.bank.reconciler.accounts")
    public static class AccountsClientProperties {
        /** Base URL of the accounts service (its {@code /internal/**} surface). */
        private String baseUrl = "http://localhost:8083";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    @Bean
    AccountsClientProperties accountsReconcileClientProperties() {
        return new AccountsClientProperties();
    }

    @Bean
    RestClient accountsReconcileRestClient(RestClient.Builder builder, AccountsClientProperties props) {
        return builder.baseUrl(props.getBaseUrl()).build();
    }
}
