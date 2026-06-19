package com.acme.bank.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.bank.contracts.avro.TransferCompleted;
import com.acme.bank.contracts.avro.TransferFailed;
import com.acme.bank.transfers.adapter.out.posting.AccountsPostingSyncClient;
import com.acme.bank.transfers.adapter.out.posting.SyncPostResult;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Fast-path branch contract. The {@link AccountsPostingSyncClient} is mocked so each outcome
 * (POSTED / REJECTED / NOT_MADE / UNKNOWN) and the eligibility/flag gating are driven deterministically.
 */
@SpringBootTest(
        properties = {
            "spring.autoconfigure.exclude=", // keep security so jwt() applies
            "acme.bank.fast-path.enabled=true",
            "acme.bank.fast-path.max-amount=1000.00",
            "acme.bank.reconciler.fixed-delay=PT1H"
        })
@AutoConfigureMockMvc
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    FastPathIT.SchemaRegistryProps.class
})
class FastPathIT {

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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    RedpandaContainer redpanda;

    @MockitoBean
    AccountsPostingSyncClient sync;

    private static String body(String amount) {
        return "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\",\"amount\":\"" + amount
                + "\",\"asset\":\"USD\"}";
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM transfer WHERE id = ?", String.class, id);
    }

    @Test
    void eligiblePostedCompletesSynchronously() throws Exception {
        when(sync.post(any())).thenReturn(SyncPostResult.posted());

        String resp = mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("100.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = mapper.readTree(resp).get("transferId").asText();

        assertThat(statusOf(id)).isEqualTo("COMPLETED");
        awaitRecord("transfer-completed", id, TransferCompleted.class);
    }

    @Test
    void eligibleRejectedFailsSynchronously() throws Exception {
        when(sync.post(any())).thenReturn(SyncPostResult.rejected("INSUFFICIENT_FUNDS"));

        String resp = mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("100.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = mapper.readTree(resp).get("transferId").asText();

        assertThat(statusOf(id)).isEqualTo("FAILED");
        awaitRecord("transfer-failed", id, TransferFailed.class);
    }

    @Test
    void ineligibleAmountUsesSlowPath() throws Exception {
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5000.00")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"));

        verify(sync, never()).post(any());
    }

    private <V> void awaitRecord(String topic, String key, Class<V> type) {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        try (Consumer<String, V> consumer = newConsumer(bootstrap, srUrl, topic + "-" + key + "-grp")) {
            consumer.subscribe(List.of(topic));
            @SuppressWarnings("unchecked")
            V[] holder = (V[]) new Object[1];
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, V> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, V> r : records) {
                    if (key.equals(r.key())) {
                        holder[0] = r.value();
                    }
                }
                assertThat(holder[0]).isNotNull();
            });
        }
    }

    private static <V> Consumer<String, V> newConsumer(String bootstrap, String srUrl, String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new KafkaConsumer<>(props);
    }
}
