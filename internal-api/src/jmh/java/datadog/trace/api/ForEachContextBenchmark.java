package datadog.trace.api;

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
 * Counts a map's number and non-number tags -- the serializers' size-counting pass -- three ways:
 * {@code forEach} with a fresh context object, the iterator with local counters, and {@code
 * forEach} with a context object reused from this state (no allocation however the JIT does).
 *
 * <p>The question is whether {@code forEach}'s reader and a per-call context object are scalar
 * replaced: if {@code forEachFreshContext} allocates nothing, {@code forEach} with a context object
 * replaces the iterator for internal iteration. Run with {@code -prof gc}, and with {@code
 * -XX:-DoEscapeAnalysis} to see what escape analysis removes. The map is built once, so only the
 * iteration is measured; {@code scenario} is the tag mix, as in {@link DenseStoreAllocBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class ForEachContextBenchmark {
  @Param({"custom", "mixed", "allSlotted"})
  String scenario;

  private TagMap map;
  private final Counts reused = new Counts();

  /** A per-call result holder, read after the iteration. */
  static final class Counts {
    int numbers;
    int others;
  }

  @Setup(Level.Trial)
  public void setup() {
    int tagCount = 12;
    int slotted =
        "allSlotted".equals(scenario)
            ? tagCount
            : "mixed".equals(scenario) ? (tagCount * 7) / 10 : 0;
    this.map = TagMap.create();
    for (int i = 0; i < tagCount; i++) {
      Object value = (i % 3 == 0) ? (Object) Integer.valueOf(i) : "value-" + i;
      if (i < slotted) {
        this.map.set(DenseStoreAllocBenchmark.SLOTTED_IDS[i], value);
      } else {
        this.map.set("custom.tag." + i, value);
      }
    }
  }

  private static void count(Counts counts, TagMap.EntryReader entry) {
    if (entry.isNumber()) {
      counts.numbers++;
    } else {
      counts.others++;
    }
  }

  @Benchmark
  public long forEachFreshContext() {
    Counts counts = new Counts();
    this.map.forEach(counts, ForEachContextBenchmark::count);
    return ((long) counts.numbers << 32) | counts.others;
  }

  @Benchmark
  public long iterator() {
    int numbers = 0;
    int others = 0;
    for (TagMap.EntryReader entry : this.map) {
      if (entry.isNumber()) {
        numbers++;
      } else {
        others++;
      }
    }
    return ((long) numbers << 32) | others;
  }

  @Benchmark
  public long forEachReusedContext() {
    Counts counts = this.reused;
    counts.numbers = 0;
    counts.others = 0;
    this.map.forEach(counts, ForEachContextBenchmark::count);
    return ((long) counts.numbers << 32) | counts.others;
  }
}
