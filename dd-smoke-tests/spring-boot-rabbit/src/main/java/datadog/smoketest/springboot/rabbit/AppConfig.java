package datadog.smoketest.springboot.rabbit;

import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@PropertySource("application.properties")
public class AppConfig {
  @Value("${rabbit.sender.queue}")
  private String senderQueueName;

  public String getSenderQueueName() {
    return senderQueueName;
  }

  @Value("${rabbit.receiver.queue}")
  private String receiverQueueName;

  @Value("${rabbit.receiver.forward}")
  private boolean receiverForwardEnabled;

  public boolean isReceiverForwardEnabled() {
    return receiverForwardEnabled;
  }

  @Bean
  Queue senderQueue() {
    return new Queue(senderQueueName);
  }

  @Bean
  Queue receiverQueue() {
    return new Queue(receiverQueueName);
  }
}
