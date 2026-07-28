package rabbit;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot application that wires an {@link ErrorReceiver} which throws on every message. Used
 * to verify that the instrumentation captures error tags on consumer spans when message processing
 * fails.
 */
@SpringBootApplication
@org.springframework.context.annotation.ComponentScan(
    basePackageClasses = ErrorMessagingRabbitMQApplication.class,
    excludeFilters =
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
            classes = MessagingRabbitMQApplication.class))
public class ErrorMessagingRabbitMQApplication {

  static final String topicExchangeName = "test-error-exchange";
  static final String queueName = "test-error-queue";

  @Bean
  Queue errorQueue() {
    return new Queue(queueName, false);
  }

  @Bean
  TopicExchange errorExchange() {
    return new TopicExchange(topicExchangeName);
  }

  @Bean
  Binding errorBinding(Queue errorQueue, TopicExchange errorExchange) {
    return BindingBuilder.bind(errorQueue).to(errorExchange).with("error.bar.#");
  }

  @Bean
  ConnectionFactory connectionFactory() {
    return new CachingConnectionFactory(
        MessagingRabbitMQApplication.hostName, MessagingRabbitMQApplication.port);
  }

  @Bean
  SimpleMessageListenerContainer errorContainer(
      ConnectionFactory connectionFactory, MessageListenerAdapter errorListenerAdapter) {
    SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.setQueueNames(queueName);
    container.setMessageListener(errorListenerAdapter);
    // Do not requeue on failure so the container does not retry infinitely
    container.setDefaultRequeueRejected(false);
    return container;
  }

  @Bean
  MessageListenerAdapter errorListenerAdapter(ErrorReceiver errorReceiver) {
    return new MessageListenerAdapter(errorReceiver, "receiveMessage");
  }

  public static ConfigurableApplicationContext run() {
    return SpringApplication.run(ErrorMessagingRabbitMQApplication.class);
  }
}
