package com.acme.bank.accounts.adapter.in.web;

import an.awesome.pipelinr.Pipeline;
import com.acme.bank.accounts.adapter.in.web.AccountDtos.AccountView;
import com.acme.bank.accounts.adapter.in.web.AccountDtos.MoneyView;
import com.acme.bank.accounts.adapter.in.web.AccountDtos.OpenAccountRequest;
import com.acme.bank.accounts.adapter.in.web.AccountDtos.OpenAccountResponse;
import com.acme.bank.accounts.adapter.in.web.AccountDtos.StatementLineView;
import com.acme.bank.accounts.adapter.in.web.AccountDtos.StatementView;
import com.acme.bank.accounts.adapter.in.web.AccountDtos.StatusView;
import com.acme.bank.accounts.application.ChangeAccountStatusCommand;
import com.acme.bank.accounts.application.ChangeAccountStatusCommand.Transition;
import com.acme.bank.accounts.application.ChangeAccountStatusResult;
import com.acme.bank.accounts.application.OpenAccountCommand;
import com.acme.bank.accounts.application.OpenAccountResult;
import com.acme.bank.accounts.domain.Account;
import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.Accounts;
import com.acme.bank.accounts.domain.Ledger;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.web.error.ApiException;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    private final Pipeline pipeline;
    private final Accounts accounts;
    private final Ledger ledger;

    public AccountController(Pipeline pipeline, Accounts accounts, Ledger ledger) {
        this.pipeline = pipeline;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OpenAccountResponse open(
            @Valid @RequestBody OpenAccountRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var asset = Assets.of(request.asset());
        Money deposit = request.initialDeposit() == null
                ? null
                : Money.of(
                        request.initialDeposit().value(),
                        Assets.of(request.initialDeposit().asset()));
        String requestId =
                idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        OpenAccountResult result =
                pipeline.send(new OpenAccountCommand(requestId, request.ownerName(), asset, deposit));
        return new OpenAccountResponse(result.accountId(), result.iban(), result.status());
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable String id) {
        Account account = load(id);
        return new AccountView(
                account.id().value(), account.iban().value(), account.status().name());
    }

    @GetMapping("/{id}/balance")
    public MoneyView balance(@PathVariable String id) {
        load(id); // 404 if unknown
        Money balance = ledger.balanceOf(new AccountId(id));
        return new MoneyView(scaled(balance), balance.asset().code());
    }

    /** Exact decimal string at the asset's scale (e.g. "100.00"), never a float. */
    private static String scaled(Money money) {
        String formatted = money.format(); // "100.00 USD"
        int space = formatted.lastIndexOf(' ');
        return formatted.substring(0, space);
    }

    @GetMapping("/{id}/statement")
    public StatementView statement(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        load(id); // 404 if unknown
        AccountId accountId = new AccountId(id);
        Instant from = Instant.EPOCH;
        Instant to = Instant.now().plusSeconds(1);
        List<Ledger.PostedEntry> entries = ledger.entriesFor(accountId, from, to, page, size);

        List<StatementLineView> lines = new ArrayList<>(entries.size());
        if (!entries.isEmpty()) {
            // Running balance is correct across pages: seed from the SUM of all entries strictly
            // before this page's first entry, then fold the page in order.
            Money running = ledger.balanceBefore(accountId, entries.get(0).postedAt());
            for (Ledger.PostedEntry e : entries) {
                running = running.add(e.amount());
                lines.add(new StatementLineView(
                        e.postedAt().toString(), e.counterpartyAccountId(), scaled(e.amount()), scaled(running)));
            }
        }
        return new StatementView(id, page, size, lines);
    }

    @PostMapping("/{id}/freeze")
    public StatusView freeze(@PathVariable String id) {
        return changeStatus(id, Transition.FREEZE);
    }

    @PostMapping("/{id}/close")
    public StatusView close(@PathVariable String id) {
        return changeStatus(id, Transition.CLOSE);
    }

    private StatusView changeStatus(String id, Transition transition) {
        load(id); // 404 if unknown
        try {
            ChangeAccountStatusResult result = pipeline.send(new ChangeAccountStatusCommand(id, transition));
            return new StatusView(result.accountId(), result.status());
        } catch (IllegalStateException ex) {
            throw new ApiException(
                    AccountErrorCode.ILLEGAL_ACCOUNT_TRANSITION, Map.of("accountId", id, "reason", ex.getMessage()));
        }
    }

    private Account load(String id) {
        return accounts.findById(new AccountId(id))
                .orElseThrow(() -> new ApiException(AccountErrorCode.ACCOUNT_NOT_FOUND, Map.of("accountId", id)));
    }
}
