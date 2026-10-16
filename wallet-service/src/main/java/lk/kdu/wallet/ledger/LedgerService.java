package lk.kdu.wallet.ledger;

import lk.kdu.wallet.common.DomainErrors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LedgerService {

    public static final String CREDITED = "Credited";
    public static final String DEBITED = "Debited";

    public record BalanceView(long balanceMinor, long lastSeq) {}

    public record EntryResult(UUID eventId, long seq, long balanceMinor) {}

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createWallet(String ownerName) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO wallets (wallet_id, owner_name) VALUES (?, ?)", id, ownerName);
        jdbc.update("INSERT INTO wallet_balance (wallet_id) VALUES (?)", id);
        return id;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EntryResult append(UUID walletId, String type, long amountMinor, String traceId) {
        BalanceView current = balance(walletId);
        long newBalance = CREDITED.equals(type)
                ? current.balanceMinor() + amountMinor
                : current.balanceMinor() - amountMinor;
        if (newBalance < 0) {
            throw new DomainErrors.InsufficientFundsException();
        }

        long seq = current.lastSeq() + 1;
        UUID eventId = UUID.randomUUID();


        jdbc.update("INSERT INTO ledger_events (event_id, wallet_id, seq, type, amount_minor, trace_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                eventId, walletId, seq, type, amountMinor, traceId);

        int updated = jdbc.update("UPDATE wallet_balance SET balance_minor = ?, last_seq = ? "
                        + "WHERE wallet_id = ? AND last_seq = ?",
                newBalance, seq, walletId, current.lastSeq());
        if (updated == 0) {
            throw new DomainErrors.OptimisticConflictException();
        }

        String topic = CREDITED.equals(type) ? "wallet.credited" : "wallet.debited";
        String payload = String.format(
                "{\"eventId\":\"%s\",\"walletId\":\"%s\",\"seq\":%d,\"type\":\"%s\","
                        + "\"amountMinor\":%d,\"balanceMinor\":%d,\"traceId\":\"%s\"}",
                eventId, walletId, seq, type, amountMinor, newBalance, traceId);
        jdbc.update("INSERT INTO outbox (event_id, aggregate_id, seq, topic, payload) VALUES (?, ?, ?, ?, ?)",
                eventId, walletId, seq, topic, payload);

        return new EntryResult(eventId, seq, newBalance);
    }

    public BalanceView balance(UUID walletId) {
        BalanceView view = jdbc.query(
                "SELECT balance_minor, last_seq FROM wallet_balance WHERE wallet_id = ?",
                (ResultSetExtractor<BalanceView>) rs -> rs.next()
                        ? new BalanceView(rs.getLong(1), rs.getLong(2)) : null,
                walletId);
        if (view == null) {
            throw new DomainErrors.WalletNotFoundException(walletId);
        }
        return view;
    }

    public long replayBalance(UUID walletId) {
        balance(walletId);
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN type = 'Credited' THEN amount_minor ELSE -amount_minor END), 0)::bigint "
                        + "FROM ledger_events WHERE wallet_id = ?",
                Long.class, walletId);
        return v == null ? 0L : v;
    }

    public List<Map<String, Object>> history(UUID walletId) {
        balance(walletId);
        return jdbc.queryForList(
                "SELECT event_id::text AS event_id, seq, type, amount_minor, trace_id, created_at::text AS created_at "
                        + "FROM ledger_events WHERE wallet_id = ? ORDER BY seq",
                walletId);
    }
}