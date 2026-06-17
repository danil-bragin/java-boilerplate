package com.acme.cqrs;

import an.awesome.pipelinr.Command;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import org.springframework.core.annotation.Order;

/** Runs Jakarta Bean Validation on every command before its handler. Outermost behavior (order 10). */
@Order(10)
public class ValidationMiddleware implements Command.Middleware {

    private final Validator validator;

    public ValidationMiddleware(Validator validator) {
        this.validator = validator;
    }

    @Override
    public <R, C extends Command<R>> R invoke(C command, Next<R> next) {
        Set<ConstraintViolation<C>> violations = validator.validate(command);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return next.invoke();
    }
}
