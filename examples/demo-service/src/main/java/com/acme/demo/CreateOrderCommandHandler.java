package com.acme.demo;

import an.awesome.pipelinr.Command;
import org.springframework.stereotype.Component;

@Component
public class CreateOrderCommandHandler implements Command.Handler<CreateOrderCommand, Long> {

    private final OrderRepository orders;

    public CreateOrderCommandHandler(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Long handle(CreateOrderCommand command) {
        Order saved = orders.save(new Order(command.sku(), command.quantity()));
        return saved.getId();
    }
}
