package com.acme.httpclient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Guards the {@link BearerTokenRelayInterceptor}: it forwards the caller's JWT only to allow-listed
 * internal hosts, stays a no-op when there is no JWT (so unauthenticated/background calls are not falsely
 * authorized), and — critically — never relays the token to a host outside the allow-list (no credential
 * leak to third parties).
 */
class BearerTokenRelayInterceptorTest {

    private static void authenticate() {
        Jwt jwt = Jwt.withTokenValue("the-token")
                .header("alg", "none")
                .claim("sub", "alice")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(), "alice"));
    }

    private static RestClient.Builder builderRelayingTo(String... allowedHosts) {
        return RestClient.builder().requestInterceptor(new BearerTokenRelayInterceptor(List.of(allowedHosts)));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void relaysBearerToAllowedHost() {
        RestClient.Builder builder = builderRelayingTo("svc.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        authenticate();

        server.expect(requestTo("http://svc.test/v1/ping"))
                .andExpect(header("Authorization", "Bearer the-token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("http://svc.test/v1/ping").retrieve().toBodilessEntity();
        server.verify();
    }

    @Test
    void relaysBearerToWildcardSubdomain() {
        RestClient.Builder builder = builderRelayingTo("*.svc.cluster.local");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        authenticate();

        server.expect(requestTo("http://accounts.svc.cluster.local/v1/ping"))
                .andExpect(header("Authorization", "Bearer the-token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("http://accounts.svc.cluster.local/v1/ping").retrieve().toBodilessEntity();
        server.verify();
    }

    @Test
    void doesNotRelayToHostOutsideAllowList() {
        // The security crux: an authenticated caller hitting an external host must NOT leak the bearer token.
        RestClient.Builder builder = builderRelayingTo("svc.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        authenticate();

        server.expect(requestTo("https://attacker.example.com/api"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("https://attacker.example.com/api").retrieve().toBodilessEntity();
        server.verify();
    }

    @Test
    void relaysNothingWhenAllowListEmpty() {
        // Fail-safe: enabling relay without naming trusted hosts relays to no one.
        RestClient.Builder builder = builderRelayingTo();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        authenticate();

        server.expect(requestTo("http://svc.test/v1/ping"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("http://svc.test/v1/ping").retrieve().toBodilessEntity();
        server.verify();
    }

    @Test
    void sendsNoAuthorizationHeaderWhenContextEmpty() {
        RestClient.Builder builder = builderRelayingTo("svc.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();

        server.expect(requestTo("http://svc.test/v1/ping"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("http://svc.test/v1/ping").retrieve().toBodilessEntity();
        server.verify();
    }
}
