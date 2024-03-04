package datadog.smoketest.kafka.iast;

import static datadog.smoketest.kafka.iast.IastConfiguration.GROUP_ID;
import static datadog.smoketest.kafka.iast.IastConfiguration.JSON_TOPIC;
import static datadog.smoketest.kafka.iast.IastConfiguration.STRING_TOPIC;

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

  private final KafkaTemplate<String, String> stringTemplate;
  private final KafkaTemplate<String, IastMessage> jsonTemplate;

  public IastController(
      @Qualifier("iastStringKafkaTemplate") final KafkaTemplate<String, String> stringTemplate,
      @Qualifier("iastJsonKafkaTemplate") final KafkaTemplate<String, IastMessage> jsonTemplate) {
    this.stringTemplate = stringTemplate;
    this.jsonTemplate = jsonTemplate;
  }

  @GetMapping("/iast/kafka/string")
  public CompletableFuture<ResponseEntity<String>> string(@RequestParam("type") final String type) {
    return stringTemplate
        .send(STRING_TOPIC, type, type)
        .completable()
        .thenApply(this::handleKafkaResponse);
  }

  @GetMapping("/iast/kafka/json")
  public CompletableFuture<ResponseEntity<String>> json(@RequestParam("type") final String type) {
    final IastMessage message = new IastMessage();
    message.setValue(type);
    return jsonTemplate
        .send(JSON_TOPIC, type, message)
        .completable()
        .thenApply(this::handleKafkaResponse);
  }

  @KafkaListener(
      groupId = GROUP_ID,
      topics = STRING_TOPIC,
      containerFactory = "iastStringListenerFactory")
  public void listenString(final ConsumerRecord<String, String> record) {
    handle(record.key(), record.value());
  }

  @KafkaListener(
      groupId = GROUP_ID,
      topics = JSON_TOPIC,
      containerFactory = "iastJsonListenerFactory")
  public void listenJson(final ConsumerRecord<String, IastMessage> record) {
    final IastMessage message = record.value();
    handle(record.key(), message.getValue());
  }

  private void handle(final String key, final String value) {
    if (key.endsWith("source_key")) {
      LOGGER.info("Kafka tainted key: " + key);
    } else if (key.endsWith("source_value")) {
      LOGGER.info("Kafka tainted value: " + value);
    } else {
      throw new IllegalArgumentException("Non valid key " + key);
    }
  }

  private ResponseEntity<String> handleKafkaResponse(final SendResult<String, ?> result) {
    if (result.getRecordMetadata().hasOffset()) {
      return ResponseEntity.ok("OK");
    } else {
      return ResponseEntity.internalServerError().body("NO_OK");
    }
  }
}
