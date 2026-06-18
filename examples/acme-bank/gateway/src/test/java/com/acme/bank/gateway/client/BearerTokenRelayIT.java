package com.acme.bank.gateway.client;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Regression guard for "gateway must relay the caller's JWT to downstream resource servers".
 *
 * <p>The gateway's accounts/transfers clients call OAuth2-protected services. Before the fix the
 * outbound {@link RestClient} carried no {@code Authorization} header, so downstream returned 401
 * and the resilience4j fallback masked it as a 503 — the live e2e could not open an account. This
 * test builds the accounts {@link RestClient} exactly as {@link RestClientConfig} does and asserts
 * the bearer token from the security context is forwarded.
 */
class BearerTokenRelayIT {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void relaysBearerTokenFromSecurityContextToDownstream() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client =
                new RestClientConfig().accountsRestClientHttp(builder, accountsProps("http://accounts.test"));

        Jwt jwt = Jwt.withTokenValue("the-token")
                .header("alg", "none")
                .claim("sub", "alice")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(), "alice"));

        server.expect(requestTo("http://accounts.test/v1/accounts/acc-1/balance"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer the-token"))
                .andRespond(withSuccess("{\"value\":\"100.00\",\"asset\":\"USD\"}", MediaType.APPLICATION_JSON));

        client.get().uri("/v1/accounts/acc-1/balance").retrieve().toBodilessEntity();
        server.verify();
    }

    @Test
    void sendsNoAuthorizationHeaderWhenSecurityContextIsEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client =
                new RestClientConfig().accountsRestClientHttp(builder, accountsProps("http://accounts.test"));

        server.expect(requestTo("http://accounts.test/v1/accounts/acc-1/balance"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"value\":\"0.00\",\"asset\":\"USD\"}", MediaType.APPLICATION_JSON));

        client.get().uri("/v1/accounts/acc-1/balance").retrieve().toBodilessEntity();
        server.verify();
    }

    private static RestClientConfig.AccountsClientProperties accountsProps(String baseUrl) {
        RestClientConfig.AccountsClientProperties props = new RestClientConfig.AccountsClientProperties();
        props.setBaseUrl(baseUrl);
        return props;
    }
}
