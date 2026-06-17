package com.acme.demo;

import an.awesome.pipelinr.Pipeline;
import com.acme.web.error.ApiException;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/orders")
public class OrderController {

    private final Pipeline pipeline;
    private final OrderRepository orders;

    public OrderController(Pipeline pipeline, OrderRepository orders) {
        this.pipeline = pipeline;
        this.orders = orders;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateOrderRequest req) {
        Long id = pipeline.send(new CreateOrderCommand(req.sku(), req.quantity()));
        return Map.of("id", id, "sku", req.sku(), "quantity", req.quantity());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Order order = orders.findById(id)
                .orElseThrow(() -> new ApiException(DemoErrorCode.ORDER_NOT_FOUND, Map.of("orderId", id)));
        return Map.of("id", order.getId(), "sku", order.getSku(), "quantity", order.getQuantity());
    }
}
