package com.acme.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import an.awesome.pipelinr.Command;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

class ValidationMiddlewareTest {

    record SampleCommand(@NotBlank String name) implements Command<String> {}

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsInvalidCommand() {
        ValidationMiddleware middleware = new ValidationMiddleware(validator);
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> middleware.invoke(new SampleCommand(""), () -> "unreached"));
    }

    @Test
    void passesValidCommandThrough() {
        ValidationMiddleware middleware = new ValidationMiddleware(validator);
        String result = middleware.invoke(new SampleCommand("ok"), () -> "handled");
        assertThat(result).isEqualTo("handled");
    }
}
