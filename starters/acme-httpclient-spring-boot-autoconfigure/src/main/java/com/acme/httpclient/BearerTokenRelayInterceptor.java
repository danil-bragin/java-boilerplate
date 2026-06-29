package com.acme.httpclient;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Relays the authenticated caller's JWT as a {@code Bearer} {@code Authorization} header on outbound
 * requests, so a downstream OAuth2 resource server accepts the call. This is the canonical token-relay
 * pattern (extracted from the acme-bank gateway): without it the downstream returns 401 and a
 * resilience fallback typically masks it as a 503.
 *
 * <p>No-op when there is no JWT in the security context (e.g. a permitted path or an unauthenticated
 * background call) or when the {@code Authorization} header is already set.
 */
public class BearerTokenRelayInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public org.springframework.http.client.ClientHttpResponse intercept(
            org.springframework.http.HttpRequest request,
            byte[] body,
            org.springframework.http.client.ClientHttpRequestExecution execution)
            throws java.io.IOException {
        if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)
                && SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwtAuth
                && jwtAuth.getToken() instanceof Jwt jwt) {
            request.getHeaders().setBearerAuth(jwt.getTokenValue());
        }
        return execution.execute(request, body);
    }
}
