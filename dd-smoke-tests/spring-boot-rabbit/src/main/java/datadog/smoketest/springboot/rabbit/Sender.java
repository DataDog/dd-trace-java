package datadog.smoketest.springboot.rabbit;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class Sender {
  private final String queueName;
  private final RabbitTemplate template;

  public Sender(AppConfig config, RabbitTemplate template) {
    this.queueName = config.getSenderQueueName();
    this.template = template;
  }

  public void send(String message) {
    template.convertAndSend(queueName, message);
  }
}
