package lk.kdu.transfer.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyCleanup {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);
    private final JdbcTemplate jdbc;

    public IdempotencyCleanup(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelay = 3_600_000)
    public void purgeExpired() {
        int n = jdbc.update("DELETE FROM idempotency_keys WHERE expires_at < now()");
        if (n > 0) log.info("Purged {} expired idempotency keys", n);
    }
}