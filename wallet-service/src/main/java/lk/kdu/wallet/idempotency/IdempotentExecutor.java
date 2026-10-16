package lk.kdu.wallet.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import lk.kdu.wallet.common.DomainErrors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

@Service
public class IdempotentExecutor {

    public record Stored(int status, String body, boolean replayed) {}

    private record Existing(String requestHash, int code, String body) {}

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    public IdempotentExecutor(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    @Transactional
    public Stored execute(String scope, String key, String requestHash, Supplier<Stored> effect) {
        jdbc.update("DELETE FROM idempotency_keys WHERE scope = ? AND idem_key = ? AND expires_at < now()",
                scope, key);

        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys (scope, idem_key, request_hash, status, expires_at)
                VALUES (?, ?, ?, 'IN_PROGRESS', now() + interval '24 hours')
                ON CONFLICT (scope, idem_key) DO NOTHING
                """, scope, key, requestHash);

        if (inserted == 0) {
            Existing existing = jdbc.queryForObject(
                    "SELECT request_hash, response_code, response_body FROM idempotency_keys WHERE scope = ? AND idem_key = ?",
                    (rs, i) -> new Existing(rs.getString(1), rs.getInt(2), rs.getString(3)),
                    scope, key);
            if (existing == null || !existing.requestHash().equals(requestHash)) {
                throw new DomainErrors.KeyReuseException();
            }
            meters.counter("wallet.duplicates.suppressed", "layer", "api").increment();
            return new Stored(existing.code(), existing.body(), true);
        }

        Stored result = effect.get();
        jdbc.update("UPDATE idempotency_keys SET status = 'COMPLETED', response_code = ?, response_body = ? "
                        + "WHERE scope = ? AND idem_key = ?",
                result.status(), result.body(), scope, key);
        return result;
    }
}