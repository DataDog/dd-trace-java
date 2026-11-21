package datadog.trace.instrumentation.kafka_clients38;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.KAFKA_CONSUME;
import static datadog.trace.instrumentation.kafka_clients38.TextMapInjectAdapter.SETTER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.Writer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Per-record tracer cost of a Kafka consumer whose records' headers are in the shape a fetch gives
 * them: each {@link RecordHeader} holds slices of one shared record-batch buffer, and its key and
 * value only become a {@code String} and a {@code byte[]} when something reads them. The agent's
 * extraction is usually that something, so this is where it pays for Kafka's lazy header decoding.
 *
 * <p>Each op drains one poll batch through {@link TracingIterator}, as the instrumented {@code
 * ConsumerRecords.iterator()} does, with a default-configured {@link CoreTracer} and no agent
 * attached. Records carry the headers this tracer injects on produce (Datadog, W3C {@code
 * traceparent} / {@code tracestate}), each from a distinct producer trace.
 *
 * <p>Every op builds fresh {@link RecordHeader}s and {@link ConsumerRecord}s over the same wire
 * bytes, as each fetch does: a header caches what it decodes, so reusing headers across ops would
 * measure the cache instead. That construction is in every arm and costs the same in each.
 *
 * <ul>
 *   <li>{@code headerShape}: {@code fetched} headers hold buffer slices, as on a real consumer;
 *       {@code built} headers hold a {@code String} key and a {@code byte[]} value, as {@code
 *       KafkaConsumeScenariosBenchmark} (kafka-clients-0.11) builds them, for comparison with it.
 *   <li>{@code appHeaders}: how many application headers each record carries besides the trace
 *       headers (a content type, a schema id, a correlation id), which no propagator reads.
 * </ul>
 *
 * <p><b>Read ns/op and {@code gc.alloc.rate.norm}</b> (B/op); both are per record. For end-to-end
 * throughput, use the Kafka macro benchmark.
 *
 * <pre>
 *   ./gradlew :dd-java-agent:instrumentation:kafka:kafka-clients-3.8:jmh \
 *       -Pjmh.includes=KafkaFetchedHeadersBenchmark -Pjmh.profilers=gc -PtestJvm=21
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Threads(1)
@Fork(3)
public class KafkaFetchedHeadersBenchmark {
  /** Kafka's default {@code max.poll.records}. */
  private static final int BATCH_SIZE = 500;

  private static final String[] TOPICS = {"orders", "payments", "shipments", "inventory"};
  private static final String GROUP = "bench-group";
  private static final String CLUSTER_ID = "bench-cluster";
  private static final String BOOTSTRAP_SERVERS = "localhost:9092";
  private static final int VALUE_SIZE = 100;

  private static final String[][] APP_HEADERS = {
    {"content-type", "application/json"},
    {"schema-id", "1042"},
    {"correlation-id", "6f1c2a9e-3b7d-4c55-9a1e-0d8f2b4c7e13"},
  };

  @Param({"fetched", "built"})
  String headerShape;

  @Param({"0", "3"})
  int appHeaders;

  CoreTracer tracer;

  /** Per record, per header: the key and value as slices of the record's batch buffer. */
  ByteBuffer[][] keyBuffers;

  ByteBuffer[][] valueBuffers;

  /** The same headers decoded, for {@code built}. */
  String[][] keys;

  byte[][][] values;

  byte[] recordValue;
  long timestamp;
  boolean fetched;

  @Setup
  public void setup(Blackhole blackhole) {
    fetched = "fetched".equals(headerShape);
    tracer = CoreTracer.builder().writer(new DropWriter(blackhole)).build();
    AgentTracer.forceRegister(tracer);

    keyBuffers = new ByteBuffer[BATCH_SIZE][];
    valueBuffers = new ByteBuffer[BATCH_SIZE][];
    keys = new String[BATCH_SIZE][];
    values = new byte[BATCH_SIZE][][];
    recordValue = new byte[VALUE_SIZE];
    timestamp = System.currentTimeMillis();

    for (int i = 0; i < BATCH_SIZE; i++) {
      RecordHeaders injected = new RecordHeaders();
      for (int a = 0; a < appHeaders; a++) {
        injected.add(APP_HEADERS[a][0], APP_HEADERS[a][1].getBytes(UTF_8));
      }
      AgentSpan producer = tracer.buildSpan("bench", "kafka.produce").start();
      defaultPropagator().inject(producer, injected, SETTER);
      producer.finish();

      Header[] headers = injected.toArray();
      int n = headers.length;
      keys[i] = new String[n];
      values[i] = new byte[n][];
      keyBuffers[i] = new ByteBuffer[n];
      valueBuffers[i] = new ByteBuffer[n];
      // lay the record's headers out back to back in one buffer, as in a record batch
      ByteArrayOutputStream wire = new ByteArrayOutputStream();
      int[] keyStart = new int[n];
      int[] valueStart = new int[n];
      for (int h = 0; h < n; h++) {
        keys[i][h] = headers[h].key();
        values[i][h] = headers[h].value();
        byte[] key = keys[i][h].getBytes(UTF_8);
        wire.write(0); // stand-in for the varint length that precedes each field
        keyStart[h] = wire.size();
        wire.write(key, 0, key.length);
        wire.write(0);
        valueStart[h] = wire.size();
        wire.write(values[i][h], 0, values[i][h].length);
      }
      byte[] batch = wire.toByteArray();
      for (int h = 0; h < n; h++) {
        keyBuffers[i][h] = slice(batch, keyStart[h], keys[i][h].getBytes(UTF_8).length);
        valueBuffers[i][h] = slice(batch, valueStart[h], values[i][h].length);
      }
    }
  }

  /** A slice, as Kafka's record reader makes for each header field. */
  private static ByteBuffer slice(byte[] batch, int start, int length) {
    ByteBuffer buffer = ByteBuffer.wrap(batch);
    buffer.position(start).limit(start + length);
    return buffer.slice();
  }

  @TearDown
  public void tearDown() {
    AgentTracer.forceRegister(AgentTracer.NOOP_TRACER);
    tracer.close();
  }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  public void consume(Blackhole blackhole) {
    List<ConsumerRecord<?, ?>> batch = new ArrayList<>(BATCH_SIZE);
    for (int i = 0; i < BATCH_SIZE; i++) {
      batch.add(record(i));
    }
    Iterator<ConsumerRecord<?, ?>> records =
        new TracingIterator(
            batch.iterator(),
            KAFKA_CONSUME,
            CONSUMER_DECORATE,
            GROUP,
            CLUSTER_ID,
            BOOTSTRAP_SERVERS);
    // The last record's span finishes when hasNext() reports the end of the batch.
    while (records.hasNext()) {
      blackhole.consume(records.next());
    }
  }

  private ConsumerRecord<?, ?> record(int i) {
    int n = keys[i].length;
    Header[] headers = new Header[n];
    for (int h = 0; h < n; h++) {
      headers[h] =
          fetched
              ? new RecordHeader(keyBuffers[i][h], valueBuffers[i][h])
              : new RecordHeader(keys[i][h], values[i][h]);
    }
    return new ConsumerRecord<>(
        TOPICS[i % TOPICS.length],
        0,
        i,
        timestamp,
        TimestampType.CREATE_TIME,
        -1,
        VALUE_SIZE,
        null,
        recordValue,
        new RecordHeaders(headers),
        Optional.empty());
  }

  /**
   * Drops finished traces, handing them to the blackhole so the finish-triggered write isn't dead
   * code.
   */
  static final class DropWriter implements Writer {
    private final Blackhole blackhole;

    DropWriter(Blackhole blackhole) {
      this.blackhole = blackhole;
    }

    @Override
    public void write(List<DDSpan> trace) {
      blackhole.consume(trace);
    }

    @Override
    public void start() {}

    @Override
    public boolean flush() {
      return true;
    }

    @Override
    public void close() {}

    @Override
    public void incrementDropCounts(int spanCount) {}
  }
}
