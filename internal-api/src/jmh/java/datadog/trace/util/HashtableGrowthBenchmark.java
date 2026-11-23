package datadog.trace.util;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The cost of building a {@link Hashtable.D1} from scratch: create a table bounded to {@code bound}
 * entries, then insert {@code fill} of them. Each op builds a fresh table, so the bucket array's
 * allocation, and any growth, is part of what's measured. {@link HashtableD1Benchmark} instead
 * clears and refills one table, which keeps the array it grew.
 *
 * <p>{@link #buildFixed} uses the default sizing, which allocates {@code capacityFor(bound)}
 * buckets up front; {@link #buildGrowable} opts into growth with an initial capacity of 0, so it
 * starts at a quarter of that and doubles at most twice. Run with {@code -prof gc}: B/op is the
 * bucket arrays allocated per table, since the entries are made once in setup and reused.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class HashtableGrowthBenchmark {
  @Param({"1024", "65536"})
  int bound;

  @Param({"8", "256", "full"})
  String fill;

  private HashtableD1Benchmark.D1Counter[] entries;

  @Setup(Level.Trial)
  public void setUp() {
    final int count = "full".equals(fill) ? bound : Math.min(bound, Integer.parseInt(fill));
    entries = new HashtableD1Benchmark.D1Counter[count];
    for (int i = 0; i < count; i++) {
      entries[i] = new HashtableD1Benchmark.D1Counter("key-" + i);
    }
  }

  private Hashtable.Sizing growable;

  @Setup(Level.Trial)
  public void setUpSizing() {
    growable = Hashtable.Sizing.bounded(bound).initialCapacity(0);
  }

  @Benchmark
  public Hashtable.D1<String, HashtableD1Benchmark.D1Counter> buildFixed() {
    return fill(Hashtable.D1.createBounded(HashtableD1Benchmark.D1Counter.class, bound));
  }

  @Benchmark
  public Hashtable.D1<String, HashtableD1Benchmark.D1Counter> buildGrowable() {
    return fill(Hashtable.D1.create(HashtableD1Benchmark.D1Counter.class, growable));
  }

  private Hashtable.D1<String, HashtableD1Benchmark.D1Counter> fill(
      final Hashtable.D1<String, HashtableD1Benchmark.D1Counter> table) {
    // The entries are reused across tables: detach each from the previous table's chain, which
    // insert expects, before linking it into this one.
    for (final HashtableD1Benchmark.D1Counter entry : entries) {
      entry.setNext(null);
      table.insert(entry);
    }
    return table;
  }
}
