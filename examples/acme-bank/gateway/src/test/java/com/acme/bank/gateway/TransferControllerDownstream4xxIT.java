package com.acme.bank.gateway;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.bank.gateway.client.TransfersRestClient;
import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

/**
 * A downstream 4xx (client error) must surface to the caller as the corresponding 4xx problem+json,
 * NOT be retried, counted toward the circuit, and masked as a 503.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, TransferControllerDownstream4xxIT.Failing4xxDownstream.class})
class TransferControllerDownstream4xxIT {

    @TestConfiguration
    static class Failing4xxDownstream {
        @Bean
        @Primary
        TransfersRestClient fakeTransfersRestClient() {
            return (request, idempotencyKey) -> {
                throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null, null, null);
            };
        }
    }

    @Autowired
    MockMvc mvc;

    private static final String VALID =
            "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\",\"amount\":{\"value\":\"100.00\",\"asset\":\"USD\"}}";

    @Test
    void downstream400IsPropagatedAs400NotMaskedAs503() throws Exception {
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-4xx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
