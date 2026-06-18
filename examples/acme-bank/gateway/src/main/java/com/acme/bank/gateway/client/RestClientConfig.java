package com.acme.bank.gateway.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Builds the {@link RestClient} the gateway uses to reach the downstream transfers service. */
@Configuration
public class RestClientConfig {

    @ConfigurationProperties(prefix = "gateway.transfers")
    public static class TransfersClientProperties {
        /** Base URL of the downstream transfers service. */
        private String baseUrl = "http://localhost:8081";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    @ConfigurationProperties(prefix = "gateway.accounts")
    public static class AccountsClientProperties {
        /** Base URL of the downstream accounts service. */
        private String baseUrl = "http://localhost:8083";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    @Bean
    TransfersClientProperties transfersClientProperties() {
        return new TransfersClientProperties();
    }

    @Bean
    RestClient transfersRestClientHttp(RestClient.Builder builder, TransfersClientProperties props) {
        return builder.baseUrl(props.getBaseUrl()).build();
    }

    @Bean
    AccountsClientProperties accountsClientProperties() {
        return new AccountsClientProperties();
    }

    @Bean
    RestClient accountsRestClientHttp(RestClient.Builder builder, AccountsClientProperties props) {
        return builder.baseUrl(props.getBaseUrl()).build();
    }
}
