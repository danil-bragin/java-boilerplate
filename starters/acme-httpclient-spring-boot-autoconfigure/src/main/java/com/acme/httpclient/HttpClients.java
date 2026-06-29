package com.acme.httpclient;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Factory for declarative {@link org.springframework.web.service.annotation.HttpExchange @HttpExchange}
 * interface clients. A consumer defines a typed interface and asks this factory for a proxy — no
 * hand-rolled {@code RestClient} call sites.
 *
 * <pre>{@code
 * interface CatalogApi {
 *     @GetExchange("/products/{id}")
 *     Product product(@PathVariable String id);
 * }
 *
 * CatalogApi catalog = httpClients.create(CatalogApi.class, "https://catalog.internal");
 * Product p = catalog.product("p-1");
 * }</pre>
 *
 * <p>Every proxy is backed by the auto-configured {@link RestClient.Builder}, so it inherits the
 * starter's timeouts, Micrometer observation (tracing + metrics) and — when enabled — OAuth2 token
 * relay.
 */
public class HttpClients {

    private final RestClient.Builder builder;

    public HttpClients(RestClient.Builder builder) {
        this.builder = builder;
    }

    /**
     * Creates a proxy for the given {@code @HttpExchange} interface backed by the shared builder as-is
     * (the interface methods supply absolute or pre-based URLs).
     */
    public <T> T create(Class<T> clientType) {
        return factory(builder.build()).createClient(clientType);
    }

    /**
     * Creates a proxy for the given {@code @HttpExchange} interface rooted at {@code baseUrl}. The
     * shared builder is cloned so per-client base URLs do not leak across clients.
     */
    public <T> T create(Class<T> clientType, String baseUrl) {
        return factory(builder.clone().baseUrl(baseUrl).build()).createClient(clientType);
    }

    private static HttpServiceProxyFactory factory(RestClient client) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build();
    }
}
