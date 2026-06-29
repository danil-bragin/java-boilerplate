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
 * Guards the {@link BearerTokenRelayInterceptor}: it must forward the caller's JWT from the security
 * context as a {@code Bearer} header, and stay a no-op when there is none (so unauthenticated/background
 * calls are not falsely authorized).
 */
class BearerTokenRelayInterceptorTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void relaysBearerFromSecurityContext() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor(new BearerTokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();

        Jwt jwt = Jwt.withTokenValue("the-token")
                .header("alg", "none")
                .claim("sub", "alice")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(), "alice"));

        server.expect(requestTo("http://svc.test/v1/ping"))
                .andExpect(header("Authorization", "Bearer the-token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("http://svc.test/v1/ping").retrieve().toBodilessEntity();
        server.verify();
    }

    @Test
    void sendsNoAuthorizationHeaderWhenContextEmpty() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor(new BearerTokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();

        server.expect(requestTo("http://svc.test/v1/ping"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("http://svc.test/v1/ping").retrieve().toBodilessEntity();
        server.verify();
    }
}
