package com.acme.bank.transfers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.bank.transfers.adapter.out.posting.AccountsPostingSyncClient;
import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Flag OFF → eligible amounts STILL take the unchanged async slow-path (202 REQUESTED). */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "acme.bank.fast-path.enabled=false",
            "acme.bank.fast-path.max-amount=1000.00"
        })
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class FastPathFlagOffIT {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountsPostingSyncClient sync;

    @Test
    void flagOffForcesSlowPathEvenForEligibleAmount() throws Exception {
        mvc.perform(post("/v1/transfers")
                        .with(jwt())
                        .header("Idempotency-Key", "flag-off-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\","
                                + "\"amount\":\"100.00\",\"asset\":\"USD\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"));

        verify(sync, never()).post(any());
    }
}
