package com.acme.bank.gateway.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient}s the gateway uses to reach the downstream transfers and accounts
 * services. The downstream services are OAuth2 resource servers that require a valid JWT, so the
 * gateway relays the caller's bearer token on every outbound call (token relay). Without it the
 * downstream returns 401 and the resilience4j fallback masks it as a 503.
 */
@Configuration
public class RestClientConfig {

    /**
     * Relays the authenticated caller's JWT as a {@code Bearer} {@code Authorization} header on the
     * outbound request, so the downstream resource server accepts it. No-op when there is no JWT in
     * the security context (e.g. a permitted path) or the {@code Authorization} header is already set.
     */
    private static ClientHttpRequestInterceptor bearerTokenRelayInterceptor() {
        return (request, body, execution) -> {
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)
                    && SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwtAuth
                    && jwtAuth.getToken() instanceof Jwt jwt) {
                request.getHeaders().setBearerAuth(jwt.getTokenValue());
            }
            return execution.execute(request, body);
        };
    }

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
        return builder.baseUrl(props.getBaseUrl())
                .requestInterceptor(bearerTokenRelayInterceptor())
                .build();
    }

    @Bean
    AccountsClientProperties accountsClientProperties() {
        return new AccountsClientProperties();
    }

    @Bean
    RestClient accountsRestClientHttp(RestClient.Builder builder, AccountsClientProperties props) {
        return builder.baseUrl(props.getBaseUrl())
                .requestInterceptor(bearerTokenRelayInterceptor())
                .build();
    }
}
