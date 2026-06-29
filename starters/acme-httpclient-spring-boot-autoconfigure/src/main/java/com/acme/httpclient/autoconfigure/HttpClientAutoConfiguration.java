package com.acme.httpclient.autoconfigure;

import com.acme.httpclient.BearerTokenRelayInterceptor;
import com.acme.httpclient.HttpClientProperties;
import com.acme.httpclient.HttpClients;
import com.acme.httpclient.resilience.ResilienceDecorator;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

/**
 * One opinionated, auto-configured way to build typed outbound HTTP clients with resilience, OAuth2
 * token relay and observation baked in — replacing hand-rolled {@code RestClient} call sites.
 *
 * <p>Registers a shared {@link RestClient.Builder} (timeouts from {@link HttpClientProperties},
 * Micrometer observation when an {@link ObservationRegistry} bean is present) and a {@link HttpClients}
 * factory for declarative {@code @HttpExchange} interface clients. Two optional slices layer on top:
 *
 * <ul>
 *   <li><b>Token relay</b> — gated {@code acme.httpclient.token-relay.enabled=true} plus the
 *       resource-server JWT types on the classpath; copies the caller's bearer onto outbound calls.
 *   <li><b>Resilience</b> — gated on Resilience4j being present; exposes a {@link ResilienceDecorator}
 *       over the {@code acme-resilience} registries.
 * </ul>
 *
 * Every bean is {@code @ConditionalOnMissingBean}, so a consumer can override any piece.
 */
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(HttpClientProperties.class)
public class HttpClientAutoConfiguration {

    /**
     * The shared, observation-aware {@link RestClient.Builder}. Applies connect/read timeouts, wires the
     * {@link ObservationRegistry} when one exists (so outbound calls become traced + metered
     * observations) and applies all {@link RestClientCustomizer} beans (e.g. the token-relay customizer).
     */
    @Bean
    @ConditionalOnMissingBean
    public RestClient.Builder acmeRestClientBuilder(
            HttpClientProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<RestClientCustomizer> customizers) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.getConnectTimeout())
                .withReadTimeout(properties.getReadTimeout());
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
        observationRegistry.ifAvailable(builder::observationRegistry);
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder;
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpClients httpClients(RestClient.Builder acmeRestClientBuilder) {
        return new HttpClients(acmeRestClientBuilder);
    }

    /**
     * OAuth2 bearer token relay. Active only when {@code acme.httpclient.token-relay.enabled=true} and
     * the resource-server JWT types are on the classpath. Contributes the relay as a
     * {@link RestClientCustomizer} so it folds into {@link #acmeRestClientBuilder}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JwtAuthenticationToken.class)
    @ConditionalOnProperty(prefix = "acme.httpclient.token-relay", name = "enabled", havingValue = "true")
    static class TokenRelayConfiguration {

        @Bean
        @ConditionalOnMissingBean
        BearerTokenRelayInterceptor bearerTokenRelayInterceptor(HttpClientProperties properties) {
            return new BearerTokenRelayInterceptor(properties.getTokenRelay().getAllowedHosts());
        }

        @Bean
        RestClientCustomizer bearerTokenRelayCustomizer(BearerTokenRelayInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    /**
     * Resilience4j integration. Active only when Resilience4j is on the classpath (via
     * {@code acme-resilience}); reuses its registries (or sensible defaults) for the decorator.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(CircuitBreakerRegistry.class)
    static class ResilienceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ResilienceDecorator httpClientResilienceDecorator(
                HttpClientProperties properties,
                ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistry,
                ObjectProvider<RetryRegistry> retryRegistry) {
            return new ResilienceDecorator(
                    circuitBreakerRegistry.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                    retryRegistry.getIfAvailable(RetryRegistry::ofDefaults),
                    properties.getResilience().getDefaultInstance());
        }
    }
}
