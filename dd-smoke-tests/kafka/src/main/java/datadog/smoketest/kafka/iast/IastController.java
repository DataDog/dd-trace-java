package datadog.smoketest.kafka.iast;

import static datadog.smoketest.kafka.iast.IastConfiguration.GROUP_ID;
import static datadog.smoketest.kafka.iast.IastConfiguration.TOPIC;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class IastController {

  private static final Logger LOGGER = LoggerFactory.getLogger(IastController.class);

  private final KafkaTemplate<String, IastMessage> iastKafkaTemplate;

  public IastController(
      @Qualifier("iastKafkaTemplate") final KafkaTemplate<String, IastMessage> iastKafkaTemplate) {
    this.iastKafkaTemplate = iastKafkaTemplate;
  }

  @GetMapping("/iast/kafka")
  public CompletableFuture<ResponseEntity<String>> vulnerability(
      @RequestParam("type") final String type) {
    final IastMessage message = new IastMessage();
    message.setValue(type);
    return iastKafkaTemplate
        .send(TOPIC, type, message)
        .completable()
        .thenApply(this::handleKafkaResponse);
  }

  @KafkaListener(groupId = GROUP_ID, topics = TOPIC, containerFactory = "iastListenerFactory")
  public void listen(final ConsumerRecord<String, IastMessage> record) {
    final String type = record.key();
    final IastMessage message = record.value();
    if ("source_key".equals(type)) {
      LOGGER.info("Kafka tainted key: " + type);
    } else if ("source_value".equals(type)) {
      LOGGER.info("Kafka tainted value: " + message.getValue());
    }
  }

  private ResponseEntity<String> handleKafkaResponse(final SendResult<String, IastMessage> result) {
    if (result.getRecordMetadata().hasOffset()) {
      return ResponseEntity.ok("OK");
    } else {
      return ResponseEntity.internalServerError().body("NO_OK");
    }
  }
}
