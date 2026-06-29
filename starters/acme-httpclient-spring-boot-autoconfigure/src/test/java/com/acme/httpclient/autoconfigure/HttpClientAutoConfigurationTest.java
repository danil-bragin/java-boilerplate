package com.acme.httpclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.acme.httpclient.BearerTokenRelayInterceptor;
import com.acme.httpclient.HttpClients;
import com.acme.httpclient.resilience.ResilienceDecorator;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Wiring guard for {@link HttpClientAutoConfiguration} (mirrors the {@code ApplicationContextRunner}
 * style of {@code MoneyJacksonAutoConfigurationTest}). Proves the shared builder + factory are present,
 * that the builder is actually observation-wired (an outbound call produces a Micrometer observation),
 * and that the token-relay slice is off by default but engages under its flag.
 */
class HttpClientAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class));

    @Test
    void registersSharedBuilderAndFactory() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RestClient.Builder.class);
            assertThat(ctx).hasSingleBean(HttpClients.class);
        });
    }

    @Test
    void builderIsObservationWiredWhenRegistryPresent() {
        AtomicInteger observed = new AtomicInteger();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                observed.incrementAndGet();
            }
        });

        runner.withBean(ObservationRegistry.class, () -> registry).run(ctx -> {
            RestClient.Builder builder = ctx.getBean(RestClient.Builder.class);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("/ping")).andRespond(withSuccess());

            builder.build().get().uri("/ping").retrieve().toBodilessEntity();

            server.verify();
            assertThat(observed.get())
                    .as("the auto-configured builder must record an outbound observation")
                    .isPositive();
        });
    }

    @Test
    void tokenRelayIsOffByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(BearerTokenRelayInterceptor.class));
    }

    @Test
    void tokenRelayEngagesUnderFlag() {
        runner.withPropertyValues("acme.httpclient.token-relay.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(BearerTokenRelayInterceptor.class));
    }

    @Test
    void exposesResilienceDecoratorWhenResilience4jPresent() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(ResilienceDecorator.class));
    }

    @Test
    void builderIsOverridable() {
        RestClient.Builder custom = RestClient.builder();
        runner.withBean("customBuilder", RestClient.Builder.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBean(RestClient.Builder.class)).isSameAs(custom));
    }
}
