package com.acme.bank.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.bank.transfers.adapter.out.posting.AccountsPostingSyncClient;
import com.acme.bank.transfers.adapter.out.posting.SyncPostResult;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * BANK-22 Fix 1 — the per-source fast-path velocity guard closes the velocity bypass. The fast-path skips
 * antifraud screening, so fast-pathed transfers record no screening_decision and the antifraud VELOCITY
 * rule is blind to them. This IT proves the routing switch: with the cap at 3, the first 3 eligible
 * transfers from ONE source take the fast-path (200 COMPLETED synchronously); the 4th (cap+1) from that
 * same source is NO LONGER fast-path-eligible and is routed to the async screened slow-path (202
 * REQUESTED) — where antifraud DOES screen + enforce velocity. A DIFFERENT source is unaffected (still
 * fast). The mocked sync client is invoked exactly cap times: the over-cap transfer never posts inline.
 */
@SpringBootTest(
        properties = {
            "acme.bank.fast-path.enabled=true",
            "acme.bank.fast-path.max-amount=1000.00",
            "acme.bank.fast-path.max-velocity-per-source=3",
            "acme.bank.fast-path.velocity-window=1h",
            "acme.bank.reconciler.fixed-delay=PT1H"
        })
@AutoConfigureMockMvc
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    FastPathVelocityIT.SchemaRegistryProps.class
})
class FastPathVelocityIT {

    private static final int CAP = 3;

    @TestConfiguration
    static class SchemaRegistryProps {
        @Bean
        DynamicPropertyRegistrar schemaRegistry(RedpandaContainer redpanda) {
            return registry -> {
                registry.add(
                        "spring.kafka.consumer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
                registry.add(
                        "spring.kafka.producer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountsPostingSyncClient sync;

    private static String body(String source, String amount) {
        return "{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\"b\",\"amount\":\"" + amount
                + "\",\"asset\":\"USD\"}";
    }

    private void initiate(String source, String key, String expectStatus, int expectHttp) throws Exception {
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(source, "100.00")))
                .andExpect(status().is(expectHttp))
                .andExpect(jsonPath("$.status").value(expectStatus));
    }

    @Test
    void overCapSourceIsRoutedToAsyncSlowPath() throws Exception {
        when(sync.post(any())).thenReturn(SyncPostResult.posted());

        // The first CAP transfers from source "hot" take the fast-path → 200 COMPLETED synchronously.
        for (int i = 0; i < CAP; i++) {
            initiate("hot", "vel-hot-" + i, "COMPLETED", 200);
        }

        // The (CAP+1)-th from the SAME source exceeds the cap → routed to the async screened slow-path.
        initiate("hot", "vel-hot-over", "REQUESTED", 202);

        // The sync post was attempted exactly CAP times — the over-cap transfer never posted inline.
        verify(sync, times(CAP)).post(any());
    }

    @Test
    void differentSourceStaysFastWhenAnotherSourceIsHot() throws Exception {
        when(sync.post(any())).thenReturn(SyncPostResult.posted());

        for (int i = 0; i < CAP; i++) {
            initiate("hot2", "vel-hot2-" + i, "COMPLETED", 200);
        }
        // hot2 is now at the cap → async. But a fresh, low-velocity source is still fast.
        initiate("hot2", "vel-hot2-over", "REQUESTED", 202);
        initiate("cool", "vel-cool-1", "COMPLETED", 200);

        assertThat(true).isTrue();
    }
}
