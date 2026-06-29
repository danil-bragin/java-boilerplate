package com.acme.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.client.RestClient;
import org.springframework.web.service.annotation.GetExchange;

/**
 * Proves the declarative-client path end to end: a {@code @HttpExchange}/{@code @GetExchange} interface
 * turned into a proxy by {@link HttpClients} performs a real GET and deserializes the JSON response —
 * the reusable replacement for hand-rolled {@code RestClient} call sites.
 */
class HttpClientsTest {

    interface CatalogApi {
        @GetExchange("/products/{id}")
        Product product(@PathVariable String id);
    }

    record Product(String id, String name) {}

    @Test
    void declarativeClientPerformsGetAndDeserializes() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://catalog.test/products/p-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"p-1\",\"name\":\"Widget\"}", MediaType.APPLICATION_JSON));

        CatalogApi catalog = new HttpClients(builder).create(CatalogApi.class);
        Product product = catalog.product("p-1");

        server.verify();
        assertThat(product).isEqualTo(new Product("p-1", "Widget"));
    }

    @Test
    void createWithBaseUrlRootsTheProxy() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://catalog.test/products/p-2"))
                .andRespond(withSuccess("{\"id\":\"p-2\",\"name\":\"Gadget\"}", MediaType.APPLICATION_JSON));

        CatalogApi catalog = new HttpClients(builder).create(CatalogApi.class, "http://catalog.test");
        Product product = catalog.product("p-2");

        server.verify();
        assertThat(product.name()).isEqualTo("Gadget");
    }
}
