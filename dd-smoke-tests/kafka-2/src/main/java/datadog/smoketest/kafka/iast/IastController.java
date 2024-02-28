package datadog.smoketest.kafka.iast;

import static datadog.smoketest.kafka.iast.IastConfiguration.BYTE_ARRAY_TOPIC;
import static datadog.smoketest.kafka.iast.IastConfiguration.BYTE_BUFFER_TOPIC;
import static datadog.smoketest.kafka.iast.IastConfiguration.JSON_TOPIC;
import static datadog.smoketest.kafka.iast.IastConfiguration.STRING_TOPIC;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.requestreply.RequestReplyFuture;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class IastController {

  private static final Logger LOGGER = LoggerFactory.getLogger(IastController.class);

  private final ReplyingKafkaTemplate<String, String, String> stringTemplate;
  private final ReplyingKafkaTemplate<byte[], byte[], String> byteArrayTemplate;
  private final ReplyingKafkaTemplate<ByteBuffer, ByteBuffer, String> byteBufferTemplate;
  private final ReplyingKafkaTemplate<IastMessage, IastMessage, String> jsonTemplate;

  public IastController(
      @Qualifier("iastStringTemplate")
          final ReplyingKafkaTemplate<String, String, String> stringTemplate,
      @Qualifier("iastByteArrayTemplate")
          final ReplyingKafkaTemplate<byte[], byte[], String> byteArrayTemplate,
      @Qualifier("iastByteBufferTemplate")
          final ReplyingKafkaTemplate<ByteBuffer, ByteBuffer, String> byteBufferTemplate,
      @Qualifier("iastJsonTemplate")
          final ReplyingKafkaTemplate<IastMessage, IastMessage, String> jsonTemplate) {
    this.stringTemplate = stringTemplate;
    this.byteArrayTemplate = byteArrayTemplate;
    this.byteBufferTemplate = byteBufferTemplate;
    this.jsonTemplate = jsonTemplate;
  }

  @GetMapping("/iast/health")
  public ResponseEntity<String> health() {
    return sendAndReceive("health", null, stringTemplate, STRING_TOPIC);
  }

  @GetMapping("/iast/kafka/string")
  public ResponseEntity<String> string(@RequestParam("type") final String type) {
    return sendAndReceive(type, stringTemplate, STRING_TOPIC, Function.identity());
  }

  @GetMapping("/iast/kafka/byteArray")
  public ResponseEntity<String> byteArray(@RequestParam("type") final String type) {
    return sendAndReceive(type, byteArrayTemplate, BYTE_ARRAY_TOPIC, String::getBytes);
  }

  @GetMapping("/iast/kafka/byteBuffer")
  public ResponseEntity<String> byteBuffer(@RequestParam("type") final String type) {
    return sendAndReceive(
        type, byteBufferTemplate, BYTE_BUFFER_TOPIC, it -> ByteBuffer.wrap(it.getBytes()));
  }

  @GetMapping("/iast/kafka/json")
  public ResponseEntity<String> json(@RequestParam("type") final String type) {
    return sendAndReceive(type, jsonTemplate, JSON_TOPIC, IastMessage::new);
  }

  @KafkaListener(topics = STRING_TOPIC, containerFactory = "iastStringListener")
  @SendTo
  public String listenString(final ConsumerRecord<String, String> record) {
    String key = record.key();
    String value = record.value();
    return handle(STRING_TOPIC, key, value);
  }

  @KafkaListener(topics = BYTE_ARRAY_TOPIC, containerFactory = "iastByteArrayListener")
  @SendTo
  public String listenByteArray(final ConsumerRecord<byte[], byte[]> record) {
    byte[] key = record.key();
    byte[] value = record.value();
    return handle(BYTE_ARRAY_TOPIC, new String(key), value == null ? null : new String(value));
  }

  @KafkaListener(topics = BYTE_BUFFER_TOPIC, containerFactory = "iastByteBufferListener")
  @SendTo
  public String listenByteBuffer(final ConsumerRecord<ByteBuffer, ByteBuffer> record) {
    ByteBuffer key = record.key();
    ByteBuffer value = record.value();
    return handle(
        BYTE_BUFFER_TOPIC,
        new String(key.array(), key.arrayOffset(), key.limit()),
        value == null ? null : new String(value.array(), value.arrayOffset(), value.limit()));
  }

  @KafkaListener(topics = JSON_TOPIC, containerFactory = "iastJsonListener")
  @SendTo
  public String listenJson(final ConsumerRecord<IastMessage, IastMessage> record) {
    final IastMessage key = record.key();
    final IastMessage value = record.value();
    return handle(JSON_TOPIC, key.getValue(), value == null ? null : value.getValue());
  }

  private <E> ResponseEntity<String> sendAndReceive(
      final String type,
      final ReplyingKafkaTemplate<E, E, String> template,
      final String topic,
      final Function<String, E> mapper) {
    final boolean isKey = isKey(type);
    final String key = isKey ? type : "mock key";
    final String value = !isKey ? type : "mock value";
    LOGGER.info("Sending message to {}: {} {}", topic, key, value);
    return sendAndReceive(mapper.apply(key), mapper.apply(value), template, topic);
  }

  private <E> ResponseEntity<String> sendAndReceive(
      final E key,
      final E value,
      final ReplyingKafkaTemplate<E, E, String> template,
      final String topic) {
    final ProducerRecord<E, E> record = new ProducerRecord<>(topic, key, value);
    final RequestReplyFuture<E, E, String> future = template.sendAndReceive(record);
    try {
      future.getSendFuture().get(10, TimeUnit.SECONDS); // send ok
      final ConsumerRecord<E, String> reply = future.get(10, TimeUnit.SECONDS); // reply
      if (reply == null || !"OK".equals(reply.value())) {
        return ResponseEntity.internalServerError()
            .body(reply == null ? "REPLY_TIMEOUT" : reply.value());
      } else {
        return ResponseEntity.ok("OK");
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String handle(final String topic, final String key, final String value) {
    LOGGER.info("Received message from {}: {} {}", topic, key, value);
    if (isKey(key)) {
      LOGGER.info("Kafka tainted key: " + key);
      return "OK";
    } else if (isValue(value)) {
      LOGGER.info("Kafka tainted value: " + value);
      return "OK";
    } else if ("health".equals(key)) {
      return "OK";
    }
    return "NO_OK";
  }

  private boolean isKey(final String key) {
    return key != null && key.endsWith("source_key");
  }

  private boolean isValue(final String value) {
    return value != null && value.endsWith("source_value");
  }
}
