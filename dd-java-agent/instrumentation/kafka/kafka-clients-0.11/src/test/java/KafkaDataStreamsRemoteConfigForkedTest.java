import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.KAFKA_CLUSTER_ID;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.DataStreamsRemoteConfigTracer;
import datadog.trace.api.datastreams.KafkaConfigReport;
import datadog.trace.core.DDSpan;
import datadog.trace.core.datastreams.StatsGroup;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.PollingConditions;
import java.util.List;
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

  /** Frequent metadata updates, which report the pending client configs. */
  private static final String METADATA_MAX_AGE_MS = "500";

  private static final String MUTATED_CLIENT_ID = "mutated-after-construction";

  private static DataStreamsRemoteConfigTracer dataStreams;

  private KafkaEmbedded embeddedKafka;

  /** Installs a tracer with a recording DSM, since AbstractInstrumentationTest has none. */
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
      consumer.assign(singletonList(new TopicPartition(TOPIC, 0)));
      // The reported config is Kafka's ConsumerConfig, so later map changes don't show
      consumerProps.put(ConsumerConfig.CLIENT_ID_CONFIG, MUTATED_CLIENT_ID);

      // data streams is off
      ConsumerRecord<String, String> offRecord = produceAndConsume(producer, consumer, "off");
      assertNull(offRecord.headers().lastHeader(PROPAGATION_KEY_BASE64));
      assertNull(consumeSpan().getTag(KAFKA_CLUSTER_ID));
      assertEquals(0, dataStreams.settledGroupCount());

      // remote config enables data streams
      dataStreams.toggleDataStreams(true);
      ConsumerRecord<String, String> onRecord = produceAndConsume(producer, consumer, "on");
      assertNotNull(onRecord.headers().lastHeader(PROPAGATION_KEY_BASE64));
      assertNotNull(consumeSpan().getTag(KAFKA_CLUSTER_ID));
      new PollingConditions(5)
          .eventually(() -> {
            assertTrue(dataStreams.hasGroup("direction:out", "topic:" + TOPIC, "type:kafka"));
            assertTrue(dataStreams.hasGroup("direction:in", "topic:" + TOPIC, "type:kafka"));
          });
      // The consumer continues the pathway the producer started
      StatsGroup outbound = dataStreams.group("direction:out", "topic:" + TOPIC, "type:kafka");
      StatsGroup inbound = dataStreams.group("direction:in", "topic:" + TOPIC, "type:kafka");
      assertNotEquals(0L, outbound.getHash());
      assertEquals(outbound.getHash(), inbound.getParentHash());
      // Configs captured while DSM was off are kept until a metadata update after the enable
      dataStreams.waitForKafkaConfigReports(() -> consumer.poll(100));
      Map<String, String> consumerConfig = dataStreams.kafkaConfig("kafka_consumer");
      // Defaults only appear in the ConsumerConfig, not in the caller's map
      assertTrue(consumerConfig.containsKey(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG));
      assertNotEquals(MUTATED_CLIENT_ID, consumerConfig.get(ConsumerConfig.CLIENT_ID_CONFIG));

      // remote config disables data streams again
      dataStreams.toggleDataStreams(false);
      int groupsBeforeOffAgain = dataStreams.settledGroupCount();
      ConsumerRecord<String, String> offAgainRecord =
          produceAndConsume(producer, consumer, "off again");
      assertNull(offAgainRecord.headers().lastHeader(PROPAGATION_KEY_BASE64));
      assertNull(consumeSpan().getTag(KAFKA_CLUSTER_ID));
      // Stats stop too: no new group is written once DSM is off
      assertEquals(groupsBeforeOffAgain, dataStreams.settledGroupCount());
    }
  }

  @Test
  void consumerGroupMembershipJoinedWhileOffIsReportedOnceAfterTheEnable() throws Exception {
    Map<String, Object> consumerProps =
        KafkaTestUtils.consumerProps("subscriber", "false", embeddedKafka);
    consumerProps.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, METADATA_MAX_AGE_MS);
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
      consumer.subscribe(singletonList(TOPIC));
      // data streams is off while the consumer joins its group
      new PollingConditions(15)
          .delay(0)
          .eventually(() -> {
            consumer.poll(100);
            assertFalse(consumer.assignment().isEmpty());
          });

      // remote config enables data streams, then a metadata update reports without a rebalance
      dataStreams.toggleDataStreams(true);
      List<KafkaConfigReport> members =
          dataStreams.waitForKafkaConsumerGroupMemberReports(() -> consumer.poll(100));
      assertEquals(1, members.size());
      KafkaConfigReport member = members.get(0);
      assertEquals("subscriber", member.getConsumerGroup());
      assertFalse(member.getMemberId().isEmpty());
      // The first generation: the membership from the join while off
      assertEquals(1, member.getGenerationId());
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
