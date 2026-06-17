package com.acme.cqrs;

import an.awesome.pipelinr.Command;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wraps commands marked {@link StronglyConsistent} in a programmatic transaction (commit/rollback
 * atomically). Unmarked commands run without a transaction. Inner to validation (order 20).
 */
@Order(20)
public class TransactionMiddleware implements Command.Middleware {

    private final TransactionTemplate transactionTemplate;

    public TransactionMiddleware(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public <R, C extends Command<R>> R invoke(C command, Next<R> next) {
        if (command instanceof StronglyConsistent) {
            return transactionTemplate.execute(status -> next.invoke());
        }
        return next.invoke();
    }
}
