package com.acme.httpclient;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><strong>Security:</strong> a bearer token is the caller's identity — relaying it to the wrong host
 * leaks credentials to a third party. Relay is therefore restricted to an explicit allow-list of
 * <em>internal</em> hosts ({@code acme.httpclient.token-relay.allowed-hosts}). A host matches by exact
 * name or a leading {@code *.} wildcard (e.g. {@code *.svc.cluster.local} matches {@code a.svc.cluster.local}).
 * <strong>Fail-safe:</strong> when the allow-list is empty, <em>no</em> token is relayed (and a warning is
 * logged at construction) — enabling relay without naming the trusted hosts does nothing rather than leaking.
 *
 * <p>No-op when there is no JWT in the security context (e.g. a permitted path or an unauthenticated
 * background call), when the {@code Authorization} header is already set, or when the target host is not
 * on the allow-list.
 */
public class BearerTokenRelayInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenRelayInterceptor.class);

    private final List<String> allowedHosts;

    public BearerTokenRelayInterceptor(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        if (this.allowedHosts.isEmpty()) {
            log.warn("acme.httpclient.token-relay.enabled=true but token-relay.allowed-hosts is empty — "
                    + "no bearer token will be relayed (fail-safe). List the trusted internal hosts to enable relay.");
        }
    }

    @Override
    public org.springframework.http.client.ClientHttpResponse intercept(
            org.springframework.http.HttpRequest request,
            byte[] body,
            org.springframework.http.client.ClientHttpRequestExecution execution)
            throws java.io.IOException {
        if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)
                && isAllowedHost(request.getURI().getHost())
                && SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwtAuth
                && jwtAuth.getToken() instanceof Jwt jwt) {
            request.getHeaders().setBearerAuth(jwt.getTokenValue());
        }
        return execution.execute(request, body);
    }

    private boolean isAllowedHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String pattern : allowedHosts) {
            String p = pattern.toLowerCase(Locale.ROOT);
            if (p.equals(h)) {
                return true;
            }
            // "*.example.com" matches any sub-domain of example.com (but not example.com itself).
            if (p.startsWith("*.") && h.endsWith(p.substring(1))) {
                return true;
            }
        }
        return false;
    }
}
