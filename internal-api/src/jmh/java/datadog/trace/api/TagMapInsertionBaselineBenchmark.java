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
 * Master-equivalent control for {@link TagMapInsertionBenchmark}: string insertion / lookup with NO
 * {@link KnownTags.Resolver} registered, so every tag uses the hash buckets (no slot routing, no
 * keyOf). This mirrors how master behaves and isolates the comparison "automatic insertion by id
 * (this branch) vs the pre-feature string baseline".
 *
 * <p>Runs in its own benchmark class so each method's fork has no resolver registered (the resolver
 * is global static; {@code TagMapInsertionBenchmark} registers one in its own forks).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Threads(1)
@State(Scope.Benchmark)
public class TagMapInsertionBaselineBenchmark {
  // same tag set as TagMapInsertionBenchmark for an apples-to-apples comparison
  static final String[] NAMES = TagMapInsertionBenchmark.NAMES;

  static final Object[] VALUES = new Object[NAMES.length];

  TagMap readMap;

  @Setup(Level.Trial)
  public void setup() {
    KnownTags.register(null); // no resolver: pure string / bucket path, like master
    for (int i = 0; i < NAMES.length; ++i) {
      VALUES[i] = "value-" + i;
    }
    this.readMap = TagMap.create();
    for (int i = 0; i < NAMES.length; ++i) {
      this.readMap.set(NAMES[i], VALUES[i]);
    }
  }

  @Benchmark
  public TagMap insertByString_noResolver() {
    TagMap map = TagMap.create();
    for (int i = 0; i < NAMES.length; ++i) {
      map.set(NAMES[i], VALUES[i]);
    }
    return map;
  }

  @Benchmark
  public void getByString_noResolver(Blackhole bh) {
    for (int i = 0; i < NAMES.length; ++i) {
      bh.consume(this.readMap.getEntry(NAMES[i]));
    }
  }
}
