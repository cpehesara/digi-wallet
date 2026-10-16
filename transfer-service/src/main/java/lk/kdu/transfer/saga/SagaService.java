package lk.kdu.transfer.saga;

import lk.kdu.transfer.common.Kv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SagaService {
    private static final Logger log = LoggerFactory.getLogger(SagaService.class);

    private final JdbcTemplate jdbc;

    public SagaService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID start(UUID from, UUID to, long amountMinor) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO transfers (transfer_id, from_wallet, to_wallet, amount_minor, state) "
                + "VALUES (?, ?, ?, ?, 'PENDING')", id, from, to, amountMinor);
        enqueue(id, "DEBIT");
        return id;
    }

    @Transactional
    public void onReply(UUID id, String step, String result, String reason) {
        boolean ok = "SUCCEEDED".equals(result);
        switch (step) {
            case "DEBIT" -> {
                if (ok) {
                    if (move(id, "PENDING", "DEBITED", null)) enqueue(id, "CREDIT");
                } else {
                    move(id, "PENDING", "FAILED", reason);
                }
            }
            case "CREDIT" -> {
                if (ok) {
                    move(id, "DEBITED", "COMPLETED", null);
                } else if (move(id, "DEBITED", "COMPENSATING", reason)) {
                    enqueue(id, "REFUND");
                }
            }
            case "REFUND" -> move(id, "COMPENSATING", ok ? "COMPENSATED" : "MANUAL_REVIEW", ok ? null : reason);
            default -> log.warn("Unknown step {} for transfer {}", step, id);
        }
    }

    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void resendStuck() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT transfer_id, state FROM transfers "
                        + "WHERE state IN ('PENDING', 'DEBITED', 'COMPENSATING') "
                        + "AND updated_at < now() - interval '30 seconds' "
                        + "ORDER BY updated_at LIMIT 20 FOR UPDATE SKIP LOCKED");
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("transfer_id");
            String step = switch ((String) r.get("state")) {
                case "PENDING" -> "DEBIT";
                case "DEBITED" -> "CREDIT";
                default -> "REFUND";
            };
            log.info("Transfer {} stuck in {}, re-sending {}", id, r.get("state"), step);
            enqueue(id, step); // the wallet service dedupes on transferId:step
            jdbc.update("UPDATE transfers SET updated_at = now() WHERE transfer_id = ?", id);
        }
    }

    public Map<String, Object> get(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT transfer_id::text AS transfer_id, from_wallet::text AS from_wallet, "
                        + "to_wallet::text AS to_wallet, amount_minor, state, failure_reason, "
                        + "created_at::text AS created_at, updated_at::text AS updated_at "
                        + "FROM transfers WHERE transfer_id = ?", id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found");
        }
        return rows.get(0);
    }

    private boolean move(UUID id, String from, String to, String reason) {
        int rows = jdbc.update("UPDATE transfers SET state = ?, updated_at = now() "
                + "WHERE transfer_id = ? AND state = ?", to, id, from);
        if (rows == 1 && reason != null && !reason.isBlank()) {
            jdbc.update("UPDATE transfers SET failure_reason = ? WHERE transfer_id = ?", reason, id);
        }
        return rows == 1;
    }

    private void enqueue(UUID id, String step) {
        Map<String, Object> t = jdbc.queryForMap(
                "SELECT from_wallet, to_wallet, amount_minor FROM transfers WHERE transfer_id = ?", id);
        Object wallet = "CREDIT".equals(step) ? t.get("to_wallet") : t.get("from_wallet");
        String payload = Kv.of("transferId", id.toString(), "step", step,
                "walletId", String.valueOf(wallet), "amountMinor", String.valueOf(t.get("amount_minor")));
        long seq = switch (step) { case "DEBIT" -> 1; case "CREDIT" -> 2; default -> 3; };
        jdbc.update("INSERT INTO outbox (event_id, aggregate_id, seq, topic, payload) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, seq, "cmd." + step.toLowerCase(), payload);
    }
}