package com.acme.demo;

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

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> create(@Valid @RequestBody CreateOrderRequest req) {
        return Map.of("status", "accepted", "sku", req.sku(), "quantity", req.quantity());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        throw new ApiException(DemoErrorCode.ORDER_NOT_FOUND, Map.of("orderId", id));
    }
}
