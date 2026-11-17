import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.DataStreamsRemoteConfigTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.PollingConditions;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.rule.KafkaEmbedded;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/** Data Streams off at startup, then toggled at runtime the way remote config does. */
@WithConfig(key = "data.streams.enabled", value = "false")
class KafkaDataStreamsRemoteConfigForkedTest extends AbstractInstrumentationTest {
  private static final String TOPIC = "dsm.remote.config";
  // Frequent metadata updates, which report the pending client configs
  private static final String METADATA_MAX_AGE_MS = "500";

  private static DataStreamsRemoteConfigTracer dataStreams;

  private KafkaEmbedded embeddedKafka;

  // AbstractInstrumentationTest has no data streams support: swap in a tracer with a recording DSM
  @BeforeAll
  static void installDataStreamsTracer() {
    dataStreams = DataStreamsRemoteConfigTracer.install(tracer, writer);
    tracer = dataStreams.tracer();
  }

  @BeforeEach
  void startBroker() throws Exception {
    dataStreams.clear();
    embeddedKafka = new KafkaEmbedded(1, true, TOPIC);
    embeddedKafka.before();
  }

  @AfterEach
  void stopBroker() throws Exception {
    try {
      dataStreams.toggleDataStreams(false);
    } finally {
      if (embeddedKafka != null) {
        embeddedKafka.after();
      }
    }
  }

  @Test
  void dataStreamsFollowsTheLiveConfigForClientsBuiltWhileItWasOff() throws Exception {
    Map<String, Object> producerProps =
        KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString());
    producerProps.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, METADATA_MAX_AGE_MS);
    Map<String, Object> consumerProps =
        KafkaTestUtils.consumerProps("sender", "false", embeddedKafka);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerProps.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, METADATA_MAX_AGE_MS);
    try (KafkaProducer<String, String> producer =
            new KafkaProducer<>(producerProps, new StringSerializer(), new StringSerializer());
        KafkaConsumer<String, String> consumer =
            new KafkaConsumer<>(
                consumerProps, new StringDeserializer(), new StringDeserializer())) {
      consumer.assign(Collections.singletonList(new TopicPartition(TOPIC, 0)));

      // data streams is off
      ConsumerRecord<String, String> offRecord = produceAndConsume(producer, consumer, "off");
      assertNull(offRecord.headers().lastHeader(PROPAGATION_KEY_BASE64));
      assertNull(consumeSpan().getTag(InstrumentationTags.KAFKA_CLUSTER_ID));
      assertEquals(0, dataStreams.settledGroupCount());

      // remote config enables data streams
      dataStreams.toggleDataStreams(true);
      ConsumerRecord<String, String> onRecord = produceAndConsume(producer, consumer, "on");
      assertNotNull(onRecord.headers().lastHeader(PROPAGATION_KEY_BASE64));
      assertNotNull(consumeSpan().getTag(InstrumentationTags.KAFKA_CLUSTER_ID));
      new PollingConditions(5)
          .eventually(() -> {
            assertTrue(dataStreams.hasGroup("direction:out", "topic:" + TOPIC, "type:kafka"));
            assertTrue(dataStreams.hasGroup("direction:in", "topic:" + TOPIC, "type:kafka"));
          });
      // Configs captured while DSM was off are kept until a metadata update after the enable
      dataStreams.waitForKafkaConfigReports(() -> consumer.poll(100));

      // remote config disables data streams again
      dataStreams.toggleDataStreams(false);
      int groupsBeforeOffAgain = dataStreams.settledGroupCount();
      ConsumerRecord<String, String> offAgainRecord =
          produceAndConsume(producer, consumer, "off again");
      assertNull(offAgainRecord.headers().lastHeader(PROPAGATION_KEY_BASE64));
      assertNull(consumeSpan().getTag(InstrumentationTags.KAFKA_CLUSTER_ID));
      // Stats stop too: no new group is written once DSM is off
      assertEquals(groupsBeforeOffAgain, dataStreams.settledGroupCount());
    }
  }

  private static ConsumerRecord<String, String> produceAndConsume(
      KafkaProducer<String, String> producer, KafkaConsumer<String, String> consumer, String value)
      throws Exception {
    writer.clear();
    producer.send(new ProducerRecord<>(TOPIC, 0, null, value)).get();
    AtomicReference<ConsumerRecord<String, String>> consumed = new AtomicReference<>();
    new PollingConditions(10)
        .delay(0)
        .eventually(() -> {
          // iterating to the end finishes the consume span
          for (ConsumerRecord<String, String> record : consumer.poll(100)) {
            if (value.equals(record.value())) {
              consumed.set(record);
            }
          }
          assertNotNull(consumed.get());
        });
    return consumed.get();
  }

  /** The single consume span written since the last {@link #produceAndConsume}. */
  private static DDSpan consumeSpan() {
    return dataStreams.singleSpan("kafka.consume");
  }
}
