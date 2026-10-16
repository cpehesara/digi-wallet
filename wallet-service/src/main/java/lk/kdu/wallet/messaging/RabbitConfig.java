package lk.kdu.wallet.messaging;

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

    public static final String EXCHANGE = "wallet.exchange";
    public static final String COMMAND_EXCHANGE = "transfer.exchange";

    public static final String QUEUE = "wallet.events.notification";
    public static final String COMMAND_QUEUE = "wallet.commands";

    public static final String DLX = "wallet.dlx";
    public static final String DLQ = "wallet.events.notification.dlq";
    public static final String COMMAND_DLQ = "wallet.commands.dlq";

    @Bean
    public TopicExchange walletExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange commandExchange() {
        return new TopicExchange(COMMAND_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }

    @Bean
    public Queue commandQueue() {
        return QueueBuilder.durable(COMMAND_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", COMMAND_DLQ)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Queue commandDeadLetterQueue() {
        return QueueBuilder.durable(COMMAND_DLQ).build();
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue()).to(walletExchange()).with("wallet.*");
    }

    @Bean
    public Binding commandBinding() {
        return BindingBuilder.bind(commandQueue()).to(commandExchange()).with("cmd.*");
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DLQ);
    }

    @Bean
    public Binding commandDeadLetterBinding() {
        return BindingBuilder.bind(commandDeadLetterQueue()).to(deadLetterExchange()).with(COMMAND_DLQ);
    }
}