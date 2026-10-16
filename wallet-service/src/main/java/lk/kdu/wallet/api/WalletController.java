package lk.kdu.wallet.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lk.kdu.wallet.chaos.ChaosState;
import lk.kdu.wallet.common.DomainErrors;
import lk.kdu.wallet.idempotency.ConflictRetry;
import lk.kdu.wallet.idempotency.IdempotentExecutor;
import lk.kdu.wallet.idempotency.IdempotentExecutor.Stored;
import lk.kdu.wallet.idempotency.RequestHasher;
import lk.kdu.wallet.ledger.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    public record CreateWalletRequest(@NotBlank String ownerName) {}

    public record AmountRequest(@Positive long amountMinor) {}

    private static final int MAX_RETRIES = 12;

    private final IdempotentExecutor executor;
    private final LedgerService ledger;
    private final ChaosState chaos;

    public WalletController(IdempotentExecutor executor, LedgerService ledger, ChaosState chaos) {
        this.executor = executor;
        this.ledger = ledger;
        this.chaos = chaos;
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateWalletRequest req) {
        requireKey(key);
        String scope = "POST /wallets";
        String hash = RequestHasher.sha256(scope + "|" + req.ownerName());
        Stored out = ConflictRetry.run(MAX_RETRIES, () -> executor.execute(scope, key, hash, () -> {
            UUID id = ledger.createWallet(req.ownerName());
            return new Stored(201, "{\"walletId\":\"" + id + "\"}", false);
        }));
        return respond(out);
    }

    @PostMapping("/{walletId}/topups")
    public ResponseEntity<String> topUp(
            @PathVariable UUID walletId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceHeader,
            @Valid @RequestBody AmountRequest req) {
        return entry(walletId, LedgerService.CREDITED, "topups", key, traceHeader, req);
    }

    @PostMapping("/{walletId}/withdrawals")
    public ResponseEntity<String> withdraw(
            @PathVariable UUID walletId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceHeader,
            @Valid @RequestBody AmountRequest req) {
        return entry(walletId, LedgerService.DEBITED, "withdrawals", key, traceHeader, req);
    }

    @GetMapping("/{walletId}/balance")
    public Map<String, Object> balance(@PathVariable UUID walletId) {
        LedgerService.BalanceView b = ledger.balance(walletId);
        return Map.of("walletId", walletId.toString(), "currency", "LKR",
                "balanceMinor", b.balanceMinor(), "lastSeq", b.lastSeq());
    }

    @GetMapping("/{walletId}/balance/replay")
    public Map<String, Object> replay(@PathVariable UUID walletId) {
        long projection = ledger.balance(walletId).balanceMinor();
        long replayed = ledger.replayBalance(walletId);
        return Map.of("walletId", walletId.toString(),
                "projectionBalanceMinor", projection,
                "replayedBalanceMinor", replayed,
                "consistent", projection == replayed);
    }

    @GetMapping("/{walletId}/events")
    public List<Map<String, Object>> events(@PathVariable UUID walletId) {
        return ledger.history(walletId);
    }

    private ResponseEntity<String> entry(UUID walletId, String type, String action,
                                         String key, String traceHeader, AmountRequest req) {
        requireKey(key);
        String scope = "POST /wallets/" + walletId + "/" + action;
        String hash = RequestHasher.sha256(scope + "|" + req.amountMinor());
        String trace = cleanTrace(traceHeader);
        Stored out = ConflictRetry.run(MAX_RETRIES, () -> executor.execute(scope, key, hash, () -> {
            LedgerService.EntryResult r = ledger.append(walletId, type, req.amountMinor(), trace);
            return new Stored(201,
                    String.format("{\"eventId\":\"%s\",\"seq\":%d,\"balanceMinor\":%d}",
                            r.eventId(), r.seq(), r.balanceMinor()),
                    false);
        }));
        return respond(out);
    }

    private ResponseEntity<String> respond(Stored out) {
        if (chaos.dropResponse) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "chaos: response dropped after commit");
        }
        return ResponseEntity.status(out.status())
                .header("Idempotent-Replayed", String.valueOf(out.replayed()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(out.body());
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new DomainErrors.MissingIdempotencyKeyException();
        }
    }

    private static String cleanTrace(String t) {
        return (t != null && t.matches("[A-Za-z0-9_-]{1,64}")) ? t : UUID.randomUUID().toString();
    }
}