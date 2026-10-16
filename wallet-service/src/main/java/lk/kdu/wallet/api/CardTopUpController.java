package lk.kdu.wallet.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lk.kdu.wallet.common.DomainErrors;
import lk.kdu.wallet.idempotency.ConflictRetry;
import lk.kdu.wallet.idempotency.IdempotentExecutor;
import lk.kdu.wallet.idempotency.IdempotentExecutor.Stored;
import lk.kdu.wallet.idempotency.RequestHasher;
import lk.kdu.wallet.ledger.LedgerService;
import lk.kdu.wallet.provider.ProviderClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class CardTopUpController {

    public record CardTopUpRequest(@Positive long amountMinor) {}

    private final ProviderClient provider;
    private final IdempotentExecutor executor;
    private final LedgerService ledger;

    public CardTopUpController(ProviderClient provider, IdempotentExecutor executor, LedgerService ledger) {
        this.provider = provider;
        this.executor = executor;
        this.ledger = ledger;
    }

    @PostMapping("/{walletId}/card-topups")
    public ResponseEntity<String> cardTopUp(
            @PathVariable UUID walletId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CardTopUpRequest req) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new DomainErrors.MissingIdempotencyKeyException();
        }
        ledger.balance(walletId); // 404 before we charge anyone

        String chargeId = provider.charge(walletId + ":" + key, req.amountMinor());

        String scope = "POST /wallets/" + walletId + "/card-topups";
        String hash = RequestHasher.sha256(scope + "|" + req.amountMinor());
        Stored out = ConflictRetry.run(12, () -> executor.execute(scope, key, hash, () -> {
            LedgerService.EntryResult r = ledger.append(walletId, LedgerService.CREDITED,
                    req.amountMinor(), "card-" + chargeId);
            return new Stored(201, String.format(
                    "{\"eventId\":\"%s\",\"seq\":%d,\"balanceMinor\":%d,\"chargeId\":\"%s\"}",
                    r.eventId(), r.seq(), r.balanceMinor(), chargeId), false);
        }));
        return ResponseEntity.status(out.status())
                .header("Idempotent-Replayed", String.valueOf(out.replayed()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(out.body());
    }

    @GetMapping("/provider-status")
    public Map<String, String> providerStatus() {
        return Map.of("circuitBreaker", provider.breakerState());
    }

    @ExceptionHandler(ProviderClient.ProviderUnavailableException.class)
    public ResponseEntity<Map<String, String>> providerDown(ProviderClient.ProviderUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "PROVIDER_UNAVAILABLE", "message", e.getMessage()));
    }
}