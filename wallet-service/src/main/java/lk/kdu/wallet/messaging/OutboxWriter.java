package lk.kdu.wallet.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class OutboxWriter {
    private final JdbcTemplate jdbc;

    public OutboxWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID aggregateId, long seq, String topic, String payload) {
        jdbc.update("INSERT INTO outbox (event_id, aggregate_id, seq, topic, payload) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), aggregateId, seq, topic, payload);
    }
}