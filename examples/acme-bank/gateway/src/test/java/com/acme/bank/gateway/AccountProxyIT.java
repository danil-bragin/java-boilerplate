package com.acme.bank.gateway;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.bank.gateway.api.dto.AccountView;
import com.acme.bank.gateway.api.dto.Money;
import com.acme.bank.gateway.api.dto.OpenAccountRequest;
import com.acme.bank.gateway.api.dto.StatementPage;
import com.acme.bank.gateway.client.AccountsRestClient;
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
import org.springframework.web.client.HttpServerErrorException;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, AccountProxyIT.FakeAccounts.class})
class AccountProxyIT {

    @TestConfiguration
    static class FakeAccounts {
        @Bean
        @Primary
        AccountsRestClient fakeAccountsRestClient() {
            return new AccountsRestClient() {
                @Override
                public AccountView open(OpenAccountRequest request, String idempotencyKey) {
                    return new AccountView("acc-new", "ACME000000000000001", AccountView.StatusEnum.OPEN);
                }

                @Override
                public AccountView get(String id) {
                    if ("missing".equals(id)) {
                        throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null);
                    }
                    if ("boom".equals(id)) {
                        throw HttpServerErrorException.create(
                                HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null);
                    }
                    return new AccountView(id, "ACME000000000000001", AccountView.StatusEnum.OPEN);
                }

                @Override
                public Money balance(String id) {
                    return new Money("100.00", "USD");
                }

                @Override
                public StatementPage statement(String id, Integer page, Integer size) {
                    return new StatementPage().accountId(id).page(page).size(size);
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    private static final String OPEN =
            "{\"ownerName\":\"Ada\",\"asset\":\"USD\",\"initialDeposit\":{\"value\":\"100.00\",\"asset\":\"USD\"}}";

    @Test
    void openAccountProxiesAndReturns201() throws Exception {
        mvc.perform(post("/v1/accounts")
                        .with(jwt())
                        .header("Idempotency-Key", "idem-acc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value("acc-new"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(post("/v1/accounts")
                        .header("Idempotency-Key", "idem-acc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void balanceProxiesAndReturns200() throws Exception {
        mvc.perform(get("/v1/accounts/{id}/balance", "acc-1").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("100.00"))
                .andExpect(jsonPath("$.asset").value("USD"));
    }

    @Test
    void downstream404PropagatesAs404() throws Exception {
        mvc.perform(get("/v1/accounts/{id}", "missing").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void downstream5xxBecomes503() throws Exception {
        mvc.perform(get("/v1/accounts/{id}", "boom").with(jwt())).andExpect(status().isServiceUnavailable());
    }
}
