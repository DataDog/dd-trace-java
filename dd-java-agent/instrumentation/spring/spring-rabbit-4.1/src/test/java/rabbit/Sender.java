package rabbit;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class Sender {

  private final RabbitTemplate template;

  public Sender(RabbitTemplate template) {
    this.template = template;
  }

  public void send(String route, String message) {
    template.convertAndSend(
        MessagingRabbitMQApplication.topicExchangeName, "foo.bar." + route, message);
  }
}
