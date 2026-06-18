package com.acme.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import an.awesome.pipelinr.Command;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class TransactionMiddlewareTest {

    record StronglyConsistentCommand(String name) implements Command<String>, StronglyConsistent {}

    record EventuallyConsistentCommand(String name) implements Command<String>, EventuallyConsistent {}

    record UnmarkedCommand(String name) implements Command<String> {}

    @Test
    void stronglyConsistentCommandOpensTransaction() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionMiddleware middleware = new TransactionMiddleware(new TransactionTemplate(txManager));

        String result = middleware.invoke(new StronglyConsistentCommand("x"), () -> "handled");

        assertThat(result).isEqualTo("handled");
        verify(txManager).getTransaction(any(TransactionDefinition.class));
    }

    @Test
    void eventuallyConsistentCommandRunsWithoutTransaction() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionMiddleware middleware = new TransactionMiddleware(new TransactionTemplate(txManager));

        String result = middleware.invoke(new EventuallyConsistentCommand("x"), () -> "handled");

        assertThat(result).isEqualTo("handled");
        // The proof: no transaction was ever begun for an EventuallyConsistent command.
        verify(txManager, never()).getTransaction(any());
    }

    @Test
    void unmarkedCommandRunsWithoutTransaction() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionMiddleware middleware = new TransactionMiddleware(new TransactionTemplate(txManager));

        String result = middleware.invoke(new UnmarkedCommand("x"), () -> "handled");

        assertThat(result).isEqualTo("handled");
        verify(txManager, never()).getTransaction(any());
    }
}
