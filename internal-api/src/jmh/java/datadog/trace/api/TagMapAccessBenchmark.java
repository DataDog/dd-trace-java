package datadog.trace.api;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Throughput microbenchmark for the core {@link TagMap} access paths: insert, raw-value read, and
 * Entry read, over a representative HTTP-server-ish tag set.
 *
 * <p><b>Threading correctness.</b> Runs at {@code @Threads(8)}. All <i>shared</i> state is
 * immutable ({@link #NAMES}/{@link #VALUES}); every bit of <i>mutable</i> state lives in a
 * {@code @State(Scope.Thread)} holder so threads never contend on a shared map, index, or reader
 * flyweight. Earlier TagMap benchmarks shared a cross-thread counter/index, which turned the result
 * into a contention measurement rather than a TagMap measurement — this layout avoids that. Indices
 * are plain per-invocation locals.
 *
 * <p>Run configuration is baked into annotations rather than relying on {@code -Pjmh.*} flags
 * (which the {@code me.champeau.jmh} plugin ignores).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Threads(8)
@State(Scope.Benchmark)
public class TagMapAccessBenchmark {
  // a representative HTTP-server-ish tag set (immutable -> safe to share across threads)
  static final String[] NAMES = {
    "http.request.method",
    "http.response.status_code",
    "http.route",
    "url.path",
    "url.scheme",
    "server.address",
    "server.port",
    "client.address",
    "network.protocol.version",
    "user_agent.original",
    "span.kind",
    "component",
    "language",
    "error",
    "resource.name",
    "service.name",
    "operation.name",
    "env",
  };

  static final Object[] VALUES = new Object[NAMES.length];

  static {
    for (int i = 0; i < NAMES.length; ++i) {
      VALUES[i] = "value-" + i;
    }
  }

  /**
   * Pre-populated read map, PER-THREAD ({@code Scope.Thread}): each thread owns its own map so
   * reads don't contend on shared mutable state under {@code @Threads(8)}.
   */
  @State(Scope.Thread)
  public static class ReadMap {
    TagMap map;

    @Setup(Level.Trial)
    public void build() {
      this.map = TagMap.create();
      for (int i = 0; i < NAMES.length; ++i) {
        this.map.set(NAMES[i], VALUES[i]);
      }
    }
  }

  @Benchmark
  public TagMap insert() {
    TagMap map = TagMap.create();
    for (int i = 0; i < NAMES.length; ++i) {
      map.set(NAMES[i], VALUES[i]);
    }
    return map;
  }

  @Benchmark
  public void getObject(ReadMap rm, Blackhole bh) {
    for (int i = 0; i < NAMES.length; ++i) {
      bh.consume(rm.map.getObject(NAMES[i]));
    }
  }

  @Benchmark
  public void getEntry(ReadMap rm, Blackhole bh) {
    for (int i = 0; i < NAMES.length; ++i) {
      bh.consume(rm.map.getEntry(NAMES[i]).objectValue());
    }
  }
}
