package lk.kdu.wallet.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import lk.kdu.wallet.chaos.ChaosState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationConsumer {
    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final ChaosState chaos;

    public NotificationConsumer(JdbcTemplate jdbc, MeterRegistry meters, ChaosState chaos) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.chaos = chaos;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    @Transactional
    public void onWalletEvent(String payload, @Header(AmqpHeaders.MESSAGE_ID) String messageId) {
        if (chaos.failConsumer) {
            throw new IllegalStateException("chaos: consumer failure");
        }

        int inserted = jdbc.update(
                "INSERT INTO inbox (consumer, message_id) VALUES ('notification', ?) ON CONFLICT DO NOTHING",
                messageId);
        if (inserted == 0) {
            meters.counter("wallet.duplicates.suppressed", "layer", "inbox").increment();
            log.info("Duplicate message {} skipped", messageId);
            return;
        }

        jdbc.update("INSERT INTO notification_log (message_id, body) VALUES (?, ?)", messageId, payload);
        log.info("Notification sent for message {}", messageId);
    }
}