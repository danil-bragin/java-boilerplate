package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import an.awesome.pipelinr.Pipeline;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import({PostgresTestcontainersConfiguration.class, RedpandaTestcontainersConfiguration.class})
class OrderProjectionIT {

    @Autowired
    Pipeline pipeline;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OrderProjectionListener listener;

    @Autowired
    ObjectMapper mapper;

    @Test
    void orderCreationFlowsThroughKafkaToTheProjectionExactlyOnce() {
        Long id = pipeline.send(new CreateOrderCommand("SKU-PROJ", 5));

        Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            Integer count =
                    jdbc.queryForObject("SELECT count(*) FROM order_projection WHERE order_id = ?", Integer.class, id);
            assertThat(count).isEqualTo(1);
        });

        String sku = jdbc.queryForObject("SELECT sku FROM order_projection WHERE order_id = ?", String.class, id);
        assertThat(sku).isEqualTo("SKU-PROJ");
    }

    @Test
    void duplicateDeliveryAppliesProjectionOnce() throws Exception {
        String json = mapper.writeValueAsString(new OrderCreated(987654L, "SKU-DUP", 1));

        listener.on(json);
        listener.on(json); // redelivery of the same event

        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM order_projection WHERE order_id = ?", Integer.class, 987654L);
        assertThat(count).isEqualTo(1);
    }
}
