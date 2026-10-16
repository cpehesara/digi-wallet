package lk.kdu.transfer.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "transfer.exchange";
    public static final String WALLET_EXCHANGE = "wallet.exchange";

    public static final String REPLY_QUEUE = "transfer.replies";
    public static final String DLX = "transfer.dlx";
    public static final String DLQ = "transfer.replies.dlq";

    @Bean
    public TopicExchange transferExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange walletExchange() {
        return new TopicExchange(WALLET_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue replyQueue() {
        return QueueBuilder.durable(REPLY_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding replyBinding() {
        return BindingBuilder.bind(replyQueue()).to(walletExchange()).with("reply.*");
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DLQ);
    }
}