package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import an.awesome.pipelinr.Command;
import an.awesome.pipelinr.Pipeline;
import com.acme.cqrs.StronglyConsistent;
import com.acme.test.PostgresTestcontainersConfiguration;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import({PostgresTestcontainersConfiguration.class, CreateOrderCommandIT.FailingConfig.class})
class CreateOrderCommandIT {

    @Autowired
    Pipeline pipeline;

    @Autowired
    OrderRepository orders;

    @Test
    void dispatchingCommandPersistsOrder() {
        long before = orders.count();
        Long id = pipeline.send(new CreateOrderCommand("SKU-CQRS", 2));
        assertThat(id).isNotNull();
        assertThat(orders.count()).isEqualTo(before + 1);
        assertThat(orders.findById(id)).get().extracting(Order::getSku).isEqualTo("SKU-CQRS");
    }

    @Test
    void validationMiddlewareRejectsInvalidCommand() {
        assertThatThrownBy(() -> pipeline.send(new CreateOrderCommand("", 0)))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void stronglyConsistentCommandRollsBackOnFailure() {
        long before = orders.count();
        assertThatThrownBy(() -> pipeline.send(new FailingOrderCommand("SKU-ROLLBACK")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orders.count()).isEqualTo(before);
    }

    /** Test-only strongly-consistent command whose handler saves then fails. */
    record FailingOrderCommand(String sku) implements Command<Void>, StronglyConsistent {}

    @TestConfiguration
    static class FailingConfig {
        @Bean
        FailingOrderCommandHandler failingOrderCommandHandler(OrderRepository orders) {
            return new FailingOrderCommandHandler(orders);
        }
    }

    static class FailingOrderCommandHandler implements Command.Handler<FailingOrderCommand, Void> {
        private final OrderRepository orders;

        FailingOrderCommandHandler(OrderRepository orders) {
            this.orders = orders;
        }

        @Override
        public Void handle(FailingOrderCommand command) {
            orders.save(new Order(command.sku(), 1));
            throw new IllegalStateException("boom after save");
        }
    }
}
