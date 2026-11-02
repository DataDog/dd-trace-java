package datadog.trace.api.datastreams;

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Building the {@link DataStreamsTags} a Kafka consumer builds per record. With Data Streams off,
 * nothing reads the tags ({@link #dsmOff}); with it on, the checkpoint reads their hashes ({@link
 * #dsmOn}). Run with {@code -prof gc} for allocation per operation.
 *
 * <p>Results (MacBook, JDK 21, default flags, 5 forks, 4 threads):
 *
 * <pre>
 * Benchmark  eager tags (before)   lazy tags (after)
 *            ns/op       B/op      ns/op       B/op
 * dsmOff     234.7 +- 2.4  1464      7.4 +- 0.0   112
 * dsmOn      236.4 +- 4.9  1464    117.3 +- 0.5   112
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(4)
@State(Scope.Benchmark)
public class DataStreamsTagsBenchmark {
  // Per-consumer values, the same instances on every record as in the instrumentation
  final String topic = "orders";
  final String group = "order-processor";
  final String clusterId = "lkc-6ojk2q";

  @Benchmark
  public DataStreamsTags dsmOff() {
    return DataStreamsTags.create("kafka", INBOUND, topic, group, clusterId);
  }

  @Benchmark
  public DataStreamsTags dsmOn() {
    DataStreamsTags tags = DataStreamsTags.create("kafka", INBOUND, topic, group, clusterId);
    // Returned, not just its hashes, since the tags escape into the checkpoint
    tags.getHash();
    tags.getAggregationHash();
    return tags;
  }
}
