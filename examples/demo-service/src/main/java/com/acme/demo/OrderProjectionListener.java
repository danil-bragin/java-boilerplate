package com.acme.demo;

import com.acme.messaging.Inbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes externalized {@code OrderCreated} JSON from the {@code orders} topic and builds an order
 * read-model idempotently: the inbox dedups by order id, so redelivery applies the projection once.
 */
@Component
public class OrderProjectionListener {

    private static final String LISTENER = "order-projection";

    private final Inbox inbox;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OrderProjectionListener(Inbox inbox, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.inbox = inbox;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "orders",
            groupId = "demo-order-projection",
            containerFactory = "stringKafkaListenerContainerFactory")
    @Transactional
    public void on(String json) throws Exception {
        OrderCreated event = mapper.readValue(json, OrderCreated.class);
        if (inbox.firstTime(LISTENER, String.valueOf(event.orderId()))) {
            jdbc.update(
                    "INSERT INTO order_projection(order_id, sku, quantity) VALUES (?, ?, ?)",
                    event.orderId(),
                    event.sku(),
                    event.quantity());
        }
    }
}
