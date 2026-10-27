package datadog.trace.instrumentation.kafka_clients;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_CONSUME;
import static datadog.trace.instrumentation.kafka_clients.TextMapInjectAdapter.SETTER;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.Writer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
 * Per-record tracer cost of a Kafka consumer: one poll batch drained through {@link
 * TracingIterator}, as the instrumented {@code ConsumerRecords.iterator()} does, with a
 * default-configured {@link CoreTracer} and no agent attached.
 *
 * <p>Each record carries the headers this tracer injects on produce (Datadog, W3C {@code
 * traceparent} / {@code tracestate}), each from a distinct producer trace, so every record goes
 * through extraction, a consume span and the Data Streams (DSM) checkpoint path. Records cycle over
 * a few topics so per-topic work isn't a single cached value.
 *
 * <p>{@code dataStreams} sets {@code dd.data.streams.enabled}. {@code false} is the default most
 * services run. {@code true} checks that DSM users don't pay for changes made for the disabled
 * case; with no local Datadog Agent, DSM still builds tags, contexts and checkpoints, but drops the
 * stats points instead of sending them.
 *
 * <p><b>Read ns/op and {@code gc.alloc.rate.norm}</b> (B/op); both are per record. Expect the
 * relative difference between builds to be larger than in a real consumer: the records here are
 * pre-built, so Kafka's fetch and lazy header parsing (which the agent triggers by reading headers)
 * aren't measured. Quote absolute ns and bytes per record. For end-to-end throughput, use the Kafka
 * macro benchmark ({@code kafka-consumer-perf-test} under the agent).
 *
 * <p>To compare builds, run it on both: cherry-pick the commit that adds this benchmark onto the
 * baseline.
 *
 * <pre>
 *   ./gradlew :dd-java-agent:instrumentation:kafka:kafka-clients-0.11:jmh \
 *       -Pjmh.includes=KafkaConsumeScenariosBenchmark -Pjmh.profilers=gc -PtestJvm=17
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Threads(1)
@Fork(3)
public class KafkaConsumeScenariosBenchmark {
  /** Kafka's default {@code max.poll.records}. */
  private static final int BATCH_SIZE = 500;

  private static final String[] TOPICS = {"orders", "payments", "shipments", "inventory"};
  private static final String GROUP = "bench-group";
  private static final String CLUSTER_ID = "bench-cluster";
  private static final String BOOTSTRAP_SERVERS = "localhost:9092";
  private static final int VALUE_SIZE = 100;

  @Param({"false", "true"})
  boolean dataStreams;

  CoreTracer tracer;
  List<ConsumerRecord<?, ?>> batch;

  @Setup
  public void setup(Blackhole blackhole) {
    // Config is read once per JVM, so this must run before anything touches it. JMH forks a fresh
    // JVM per parameter value; running in-process (-f 0) would silently measure one setting.
    System.setProperty("dd." + DATA_STREAMS_ENABLED, String.valueOf(dataStreams));
    if (Config.get().isDataStreamsEnabled() != dataStreams) {
      throw new IllegalStateException("Config was read before setup; run with forks (not -f 0)");
    }

    // Building the tracer registers its propagators as the default ones.
    tracer = CoreTracer.builder().writer(new DropWriter(blackhole)).build();
    AgentTracer.forceRegister(tracer);
    if (traceConfig().isDataStreamsEnabled() != dataStreams) {
      throw new IllegalStateException("tracer config doesn't match dataStreams=" + dataStreams);
    }

    batch = new ArrayList<>(BATCH_SIZE);
    byte[] value = new byte[VALUE_SIZE];
    long timestamp = System.currentTimeMillis();
    for (int i = 0; i < BATCH_SIZE; i++) {
      RecordHeaders headers = new RecordHeaders();
      AgentSpan producer = tracer.buildSpan("bench", "kafka.produce").start();
      defaultPropagator().inject(producer, headers, SETTER);
      producer.finish();

      batch.add(
          new ConsumerRecord<>(
              TOPICS[i % TOPICS.length],
              0,
              i,
              timestamp,
              TimestampType.CREATE_TIME,
              null,
              -1,
              VALUE_SIZE,
              null,
              value,
              headers));
    }
  }

  @TearDown
  public void tearDown() {
    AgentTracer.forceRegister(AgentTracer.NOOP_TRACER);
    tracer.close();
  }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  public void consume(Blackhole blackhole) {
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
