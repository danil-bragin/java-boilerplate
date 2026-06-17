package com.acme.demo;

import org.springframework.modulith.events.Externalized;

/**
 * Domain event emitted when an order is created. Externalized to the Kafka topic "orders"
 * keyed by order id via Spring Modulith's event publication registry (transactional outbox).
 */
@Externalized("orders::#{#this.orderId()}")
public record OrderCreated(Long orderId, String sku, int quantity) {}
