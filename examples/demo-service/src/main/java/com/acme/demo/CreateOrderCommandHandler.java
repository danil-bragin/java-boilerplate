package com.acme.demo;

import an.awesome.pipelinr.Command;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class CreateOrderCommandHandler implements Command.Handler<CreateOrderCommand, Long> {

    private final OrderRepository orders;
    private final ApplicationEventPublisher events;

    public CreateOrderCommandHandler(OrderRepository orders, ApplicationEventPublisher events) {
        this.orders = orders;
        this.events = events;
    }

    @Override
    public Long handle(CreateOrderCommand command) {
        Order saved = orders.save(new Order(command.sku(), command.quantity()));
        events.publishEvent(new OrderCreated(saved.getId(), saved.getSku(), saved.getQuantity()));
        return saved.getId();
    }
}
