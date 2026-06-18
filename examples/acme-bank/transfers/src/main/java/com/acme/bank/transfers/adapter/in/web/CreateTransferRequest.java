package com.acme.bank.transfers.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

public record CreateTransferRequest(
        @NotBlank String sourceAccountId,
        @NotBlank String destinationAccountId,
        @NotBlank String amount,
        @NotBlank String asset) {}
