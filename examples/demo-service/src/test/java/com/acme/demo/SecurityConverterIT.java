package com.acme.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.test.PostgresTestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises {@code KeycloakJwtAuthenticationConverter} end-to-end through the real security filter
 * chain: a stub {@link JwtDecoder} turns the bearer token into a JWT whose {@code realm_access.roles}
 * equals the token value, so the converter (not the test post-processor) decides the authorities.
 * This proves the converter is actually wired into {@code oauth2ResourceServer().jwt(...)} — a gap
 * the {@code jwt()} post-processor (which bypasses the converter) cannot cover.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, SecurityConverterIT.StubDecoderConfig.class})
class SecurityConverterIT {

    @Autowired
    MockMvc mvc;

    @Test
    void realmAdminRoleGrantsAccessThroughConverter() throws Exception {
        mvc.perform(get("/v1/admin/ping").header("Authorization", "Bearer ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void realmUserRoleIsForbiddenThroughConverter() throws Exception {
        mvc.perform(get("/v1/admin/ping").header("Authorization", "Bearer USER"))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    static class StubDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .issuedAt(Instant.EPOCH)
                    .expiresAt(Instant.EPOCH.plusSeconds(3600))
                    .subject("user")
                    .claim("realm_access", Map.of("roles", List.of(token)))
                    .build();
        }
    }
}
