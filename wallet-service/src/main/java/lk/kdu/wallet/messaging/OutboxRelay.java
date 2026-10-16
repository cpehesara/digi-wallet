package lk.kdu.wallet.messaging;

import lk.kdu.wallet.chaos.ChaosState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ATTEMPTS = 10;

    private record Row(long id, UUID eventId, String topic, String payload) {}

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final ChaosState chaos;

    public OutboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, ChaosState chaos) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.chaos = chaos;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishBatch() {
        List<Row> rows = jdbc.query("""
                SELECT id, event_id, topic, payload FROM outbox
                WHERE status = 'PENDING'
                ORDER BY id
                LIMIT 50
                FOR UPDATE SKIP LOCKED
                """,
                (rs, i) -> new Row(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                        rs.getString("topic"), rs.getString("payload")));

        for (Row r : rows) {
            try {
                publish(r);
                if (chaos.duplicatePublish) {
                    publish(r);
                }
                jdbc.update("UPDATE outbox SET status = 'PUBLISHED', published_at = now() WHERE id = ?", r.id());
            } catch (AmqpConnectException e) {
                log.warn("RabbitMQ unreachable. Rows stay PENDING and will be retried.");
                return;
            } catch (Exception e) {
                log.error("Publishing outbox row {} failed: {}", r.id(), e.toString());
                jdbc.update("UPDATE outbox SET attempts = attempts + 1, "
                                + "status = CASE WHEN attempts + 1 >= ? THEN 'DEAD' ELSE status END WHERE id = ?",
                        MAX_ATTEMPTS, r.id());
            }
        }
    }

    private void publish(Row r) {
        rabbit.convertAndSend(RabbitConfig.EXCHANGE, r.topic(), r.payload(), msg -> {
            msg.getMessageProperties().setMessageId(r.eventId().toString()); // stable ID for the inbox
            return msg;
        });
    }
}