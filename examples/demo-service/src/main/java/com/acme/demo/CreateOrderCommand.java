package com.acme.demo;

import an.awesome.pipelinr.Command;
import com.acme.cqrs.StronglyConsistent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Strongly-consistent write command: creating an order must be transactional. Returns the new id. */
public record CreateOrderCommand(@NotBlank String sku, @Min(1) int quantity)
        implements Command<Long>, StronglyConsistent {}
