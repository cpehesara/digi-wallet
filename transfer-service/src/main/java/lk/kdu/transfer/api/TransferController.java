package lk.kdu.transfer.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lk.kdu.transfer.common.DomainErrors;
import lk.kdu.transfer.idempotency.ConflictRetry;
import lk.kdu.transfer.idempotency.IdempotentExecutor;
import lk.kdu.transfer.idempotency.IdempotentExecutor.Stored;
import lk.kdu.transfer.idempotency.RequestHasher;
import lk.kdu.transfer.saga.SagaService;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    public record TransferRequest(@NotNull UUID fromWalletId, @NotNull UUID toWalletId, @Positive long amountMinor) {}

    private final IdempotentExecutor executor;
    private final SagaService saga;

    public TransferController(IdempotentExecutor executor, SagaService saga) {
        this.executor = executor;
        this.saga = saga;
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody TransferRequest req) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new DomainErrors.MissingIdempotencyKeyException();
        }
        if (req.fromWalletId().equals(req.toWalletId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot transfer to the same wallet");
        }
        String scope = "POST /transfers";
        String hash = RequestHasher.sha256(scope + "|" + req.fromWalletId() + "|" + req.toWalletId()
                + "|" + req.amountMinor());
        Stored out = ConflictRetry.run(5, () -> executor.execute(scope, key, hash, () -> {
            UUID id = saga.start(req.fromWalletId(), req.toWalletId(), req.amountMinor());
            return new Stored(202, "{\"transferId\":\"" + id + "\",\"state\":\"PENDING\"}", false);
        }));
        return ResponseEntity.status(out.status())
                .header("Idempotent-Replayed", String.valueOf(out.replayed()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(out.body());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return saga.get(id);
    }
}