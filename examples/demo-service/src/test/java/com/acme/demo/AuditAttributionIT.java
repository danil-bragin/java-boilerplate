package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class AuditAttributionIT {

    @Autowired
    OrderRepository orders;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createdByIsTheAuthenticatedPrincipal() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("alice", "n/a", AuthorityUtils.NO_AUTHORITIES));

        Order saved = orders.saveAndFlush(new Order("SKU-AUDIT", 1));

        assertThat(saved.getCreatedBy()).isEqualTo("alice");
        assertThat(saved.getLastModifiedBy()).isEqualTo("alice");
    }

    @Test
    void createdByIsNullWhenUnauthenticated() {
        SecurityContextHolder.clearContext();

        Order saved = orders.saveAndFlush(new Order("SKU-ANON", 1));

        assertThat(saved.getCreatedBy()).isNull();
    }
}
