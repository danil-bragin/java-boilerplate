package com.acme.cqrs.autoconfigure;

import an.awesome.pipelinr.Command;
import an.awesome.pipelinr.CommandHandlers;
import an.awesome.pipelinr.Pipeline;
import an.awesome.pipelinr.Pipelinr;
import com.acme.cqrs.TransactionMiddleware;
import com.acme.cqrs.ValidationMiddleware;
import jakarta.validation.Validator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires a PipelinR {@link Pipeline} command bus from all Spring command-handler beans and an
 * ordered list of middleware beans (explicit pipeline; no Spring AOP, so no self-invocation trap).
 */
@AutoConfiguration(after = {TransactionAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@ConditionalOnClass(Pipeline.class)
public class CqrsAutoConfiguration {

    @Bean
    @ConditionalOnClass(Validator.class)
    @ConditionalOnMissingBean
    public ValidationMiddleware validationMiddleware(Validator validator) {
        return new ValidationMiddleware(validator);
    }

    @Bean
    @ConditionalOnBean(PlatformTransactionManager.class)
    @ConditionalOnMissingBean
    public TransactionMiddleware transactionMiddleware(PlatformTransactionManager txManager) {
        return new TransactionMiddleware(new TransactionTemplate(txManager));
    }

    @Bean
    @ConditionalOnMissingBean(Pipeline.class)
    public Pipeline pipeline(
            ObjectProvider<Command.Handler<?, ?>> handlers, ObjectProvider<Command.Middleware> middlewares) {
        List<Command.Handler> handlerList =
                handlers.orderedStream().map(h -> (Command.Handler) h).toList();
        List<Command.Middleware> middlewareList = middlewares.orderedStream().toList();
        CommandHandlers commandHandlers = handlerList::stream;
        Command.Middlewares commandMiddlewares = middlewareList::stream;
        return new Pipelinr().with(commandHandlers).with(commandMiddlewares);
    }
}
