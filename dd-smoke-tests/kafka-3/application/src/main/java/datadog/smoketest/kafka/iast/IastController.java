package datadog.smoketest.kafka.iast;

import static datadog.smoketest.kafka.iast.IastConfiguration.BYTE_ARRAY_TOPIC;
import static datadog.smoketest.kafka.iast.IastConfiguration.BYTE_BUFFER_TOPIC;
import static datadog.smoketest.kafka.iast.IastConfiguration.GROUP_ID;
import static datadog.smoketest.kafka.iast.IastConfiguration.JSON_TOPIC;
import static datadog.smoketest.kafka.iast.IastConfiguration.STRING_TOPIC;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
  private final KafkaTemplate<byte[], byte[]> byteArrayTemplate;
  private final KafkaTemplate<ByteBuffer, ByteBuffer> byteBufferTemplate;
  private final KafkaTemplate<IastMessage, IastMessage> jsonTemplate;
  private final PriorityBlockingQueue<String> queue = new PriorityBlockingQueue<>();

  public IastController(
      @Qualifier("iastStringKafkaTemplate") final KafkaTemplate<String, String> stringTemplate,
      @Qualifier("iastByteArrayKafkaTemplate")
          final KafkaTemplate<byte[], byte[]> byteArrayTemplate,
      @Qualifier("iastByteBufferKafkaTemplate")
          final KafkaTemplate<ByteBuffer, ByteBuffer> byteBufferTemplate,
      @Qualifier("iastJsonKafkaTemplate")
          final KafkaTemplate<IastMessage, IastMessage> jsonTemplate) {
    this.stringTemplate = stringTemplate;
    this.byteArrayTemplate = byteArrayTemplate;
    this.byteBufferTemplate = byteBufferTemplate;
    this.jsonTemplate = jsonTemplate;
  }

  @GetMapping("/iast/health")
  public ResponseEntity<String> health() {
    stringTemplate.send(STRING_TOPIC, "health", null);
    return ResponseEntity.ok("OK");
  }

  @GetMapping("/iast/kafka/string")
  public CompletableFuture<ResponseEntity<String>> string(@RequestParam("type") final String type) {
    return sendAndWait(type, stringTemplate, STRING_TOPIC, Function.identity());
  }

  @GetMapping("/iast/kafka/byteArray")
  public CompletableFuture<ResponseEntity<String>> byteArray(
      @RequestParam("type") final String type) {
    return sendAndWait(type, byteArrayTemplate, BYTE_ARRAY_TOPIC, String::getBytes);
  }

  @GetMapping("/iast/kafka/byteBuffer")
  public CompletableFuture<ResponseEntity<String>> byteBuffer(
      @RequestParam("type") final String type) {
    return sendAndWait(
        type, byteBufferTemplate, BYTE_BUFFER_TOPIC, it -> ByteBuffer.wrap(it.getBytes()));
  }

  @GetMapping("/iast/kafka/json")
  public CompletableFuture<ResponseEntity<String>> json(@RequestParam("type") final String type) {
    return sendAndWait(type, jsonTemplate, JSON_TOPIC, IastMessage::new);
  }

  @KafkaListener(
      groupId = GROUP_ID,
      topics = STRING_TOPIC,
      containerFactory = "iastStringListenerFactory")
  public void listenString(final ConsumerRecord<String, String> record) {
    String key = record.key();
    String value = record.value();
    handle(STRING_TOPIC, key, value);
  }

  @KafkaListener(
      groupId = GROUP_ID,
      topics = BYTE_ARRAY_TOPIC,
      containerFactory = "iastByteArrayListenerFactory")
  public void listenByteArray(final ConsumerRecord<byte[], byte[]> record) {
    byte[] key = record.key();
    byte[] value = record.value();
    handle(BYTE_ARRAY_TOPIC, new String(key), value == null ? null : new String(value));
  }

  @KafkaListener(
      groupId = GROUP_ID,
      topics = BYTE_BUFFER_TOPIC,
      containerFactory = "iastByteBufferListenerFactory")
  public void listenByteBuffer(final ConsumerRecord<ByteBuffer, ByteBuffer> record) {
    ByteBuffer key = record.key();
    ByteBuffer value = record.value();
    handle(
        BYTE_BUFFER_TOPIC,
        new String(key.array(), key.arrayOffset(), key.limit()),
        value == null ? null : new String(value.array(), value.arrayOffset(), value.limit()));
  }

  @KafkaListener(
      groupId = GROUP_ID,
      topics = JSON_TOPIC,
      containerFactory = "iastJsonListenerFactory")
  public void listenJson(final ConsumerRecord<IastMessage, IastMessage> record) {
    final IastMessage key = record.key();
    final IastMessage value = record.value();
    handle(JSON_TOPIC, key.getValue(), value == null ? null : value.getValue());
  }

  private <E> CompletableFuture<ResponseEntity<String>> sendAndWait(
      final String type,
      final KafkaTemplate<E, E> template,
      final String topic,
      final Function<String, E> mapper) {
    LOGGER.info("Sending message to: " + topic);
    final boolean isKey = isKey(type);
    final String key = isKey ? type : "mock key";
    final String value = !isKey ? type : "mock value";
    return template
        .send(topic, mapper.apply(key), mapper.apply(value))
        .toCompletableFuture()
        .thenApply(result -> handleKafkaResponse(type, result));
  }

  private void handle(final String topic, final String key, final String value) {
    LOGGER.info("Received message from {}: {} {}", topic, key, value);
    if (isKey(key)) {
      LOGGER.info("Kafka tainted key: " + key);
      queue.add(key);
    } else if (isValue(value)) {
      LOGGER.info("Kafka tainted value: " + value);
      queue.add(value);
    }
  }

  private <E> ResponseEntity<String> handleKafkaResponse(
      final String expected, final SendResult<E, E> result) {
    try {
      final String key = queue.poll(10, TimeUnit.SECONDS); // wait for Kafka to consume the message
      if (expected.equals(key) && result.getRecordMetadata().hasOffset()) {
        return ResponseEntity.ok("OK");
      } else {
        return ResponseEntity.internalServerError().body(key + " != " + expected);
      }
    } catch (InterruptedException e) {
      return ResponseEntity.internalServerError().body(e.getMessage());
    }
  }

  private boolean isKey(final String key) {
    return key != null && key.endsWith("source_key");
  }

  private boolean isValue(final String value) {
    return value != null && value.endsWith("source_value");
  }
}
