package com.acme.demo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateOrderRequest(@NotBlank String sku, @Min(1) int quantity) {}
