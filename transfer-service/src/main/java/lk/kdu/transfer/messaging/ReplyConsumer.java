package lk.kdu.transfer.messaging;

import lk.kdu.transfer.common.Kv;
import lk.kdu.transfer.saga.SagaService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class ReplyConsumer {
    private final SagaService saga;

    public ReplyConsumer(SagaService saga) { this.saga = saga; }

    @RabbitListener(queues = RabbitConfig.REPLY_QUEUE)
    public void onReply(String payload) {
        Map<String, String> m = Kv.parse(payload);
        saga.onReply(UUID.fromString(m.get("transferId")), m.get("step"), m.get("result"), m.get("reason"));
    }
}