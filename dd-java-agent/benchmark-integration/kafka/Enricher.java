import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Consume-transform-produce loop for the enricher benchmark: reads records from one topic, appends a
 * few bytes to each value, and produces the result to another topic, one record at a time on one
 * thread.
 *
 * <p>Under the agent, each record gets a consume span continued from its trace headers, and the send
 * inside the loop body gets a produce span, child of the consume span, that injects headers for the
 * next service. So one record exercises extraction, span creation and injection on the same trace.
 *
 * <p>Prints {@code enriched N records in T ms, R records/s}, timed from the first record received to
 * the last send acknowledged, so startup and the group join are excluded. It finishes the batch that
 * reaches {@code messages}, so N can exceed it by up to one poll. Exits non-zero if the topic
 * runs dry before {@code messages} records or any send fails.
 *
 * <pre>java -cp "$KAFKA_HOME/libs/*" Enricher.java bootstrap in-topic out-topic messages group [copy-headers]
 * </pre>
 *
 * <p>{@code copy-headers} (default false) forwards the input record's headers to the output record,
 * as Kafka Streams does; the tracer then replaces its own headers instead of adding them.
 */
public class Enricher {
  private static final byte[] SUFFIX = "|enriched".getBytes();
  private static final long IDLE_TIMEOUT_NANOS = Duration.ofSeconds(30).toNanos();

  public static void main(String[] args) {
    if (args.length < 5) {
      System.err.println(
          "usage: java Enricher.java bootstrap in-topic out-topic messages group [copy-headers]");
      System.exit(1);
    }
    String bootstrap = args[0];
    String inTopic = args[1];
    String outTopic = args[2];
    long messages = Long.parseLong(args[3]);
    String group = args[4];
    boolean copyHeaders = args.length > 5 && Boolean.parseBoolean(args[5]);

    Properties consumerProps = new Properties();
    consumerProps.put("bootstrap.servers", bootstrap);
    consumerProps.put("group.id", group);
    consumerProps.put("auto.offset.reset", "earliest");
    consumerProps.put("enable.auto.commit", "false");
    consumerProps.put(
        "key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    consumerProps.put(
        "value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");

    Properties producerProps = new Properties();
    producerProps.put("bootstrap.servers", bootstrap);
    producerProps.put("linger.ms", "5");
    producerProps.put("batch.size", "65536");
    producerProps.put("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
    producerProps.put(
        "value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");

    // One shared callback, so the loop itself allocates nothing per record beyond the record.
    AtomicLong failedSends = new AtomicLong();
    AtomicReference<Exception> firstFailure = new AtomicReference<>();
    Callback onSent =
        (metadata, exception) -> {
          if (exception != null) {
            failedSends.incrementAndGet();
            firstFailure.compareAndSet(null, exception);
          }
        };

    long count = 0;
    long start = 0;
    long end = 0;
    long lastRecord = System.nanoTime();
    try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps);
        KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(producerProps)) {
      consumer.subscribe(List.of(inTopic));
      while (count < messages) {
        ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(100));
        if (records.isEmpty()) {
          if (System.nanoTime() - lastRecord > IDLE_TIMEOUT_NANOS) {
            break;
          }
          continue;
        }
        lastRecord = System.nanoTime();
        if (start == 0) {
          start = lastRecord;
        }
        for (ConsumerRecord<byte[], byte[]> record : records) {
          byte[] value = record.value();
          byte[] enriched = Arrays.copyOf(value, value.length + SUFFIX.length);
          System.arraycopy(SUFFIX, 0, enriched, value.length, SUFFIX.length);
          ProducerRecord<byte[], byte[]> out =
              copyHeaders
                  ? new ProducerRecord<>(outTopic, null, record.key(), enriched, record.headers())
                  : new ProducerRecord<>(outTopic, record.key(), enriched);
          producer.send(out, onSent);
          count++;
          // No break once `messages` is reached: the instrumented iterator finishes a record's
          // consume span only when it advances or runs out, so finish the fetched batch.
        }
      }
      producer.flush();
      // The last send is acknowledged here; closing the clients (e.g. the consumer leaving its
      // group) isn't part of the work being measured.
      end = System.nanoTime();
    }
    long elapsedNanos = end - start;

    if (count < messages) {
      System.err.printf("topic ran dry: enriched %d of %d records%n", count, messages);
      System.exit(2);
    }
    if (failedSends.get() > 0) {
      System.err.printf(
          "%d of %d sends failed, first with: %s%n", failedSends.get(), count, firstFailure.get());
      System.exit(3);
    }
    System.out.printf(
        "enriched %d records in %d ms, %.0f records/s%n",
        count, elapsedNanos / 1_000_000, count * 1e9 / elapsedNanos);
  }
}
