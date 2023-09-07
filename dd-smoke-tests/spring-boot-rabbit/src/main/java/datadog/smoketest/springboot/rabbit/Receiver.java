package datadog.smoketest.springboot.rabbit;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class Receiver {

  private final boolean forwardMessage;
  private final String senderQueueName;
  private final RabbitTemplate template;
  private final LinkedBlockingQueue<String> queue;

  public Receiver(AppConfig config, RabbitTemplate template) {
    this.forwardMessage = config.isReceiverForwardEnabled();
    this.senderQueueName = config.getSenderQueueName();
    this.template = template;
    if (forwardMessage) {
      this.queue = null;
    } else {
      this.queue = new LinkedBlockingQueue<>();
    }
  }

  @RabbitListener(queues = "${rabbit.receiver.queue}")
  public void receiveMessage(String msg) {
    if (forwardMessage) {
      template.convertAndSend(senderQueueName, ">" + msg);
    } else {
      queue.add(msg);
    }
  }

  public String poll(long timeoutMillis) throws InterruptedException {
    return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
  }
}
