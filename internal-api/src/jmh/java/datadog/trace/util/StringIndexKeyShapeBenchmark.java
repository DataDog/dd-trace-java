package datadog.trace.util;

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * {@link StringIndex} lookups by key shape: sequential names that differ only in a trailing counter
 * ({@code scale-bulk-00001}, {@code scale-bulk-00002}, ...), as generated flag keys and ids are,
 * against random names of the same length.
 *
 * <p>Sequential names have nearly equal {@code String.hashCode}s. Unless the hash spread mixes them
 * apart, they fill runs of adjacent slots, and linear probing walks those runs: a hit probes until
 * it reaches its key, and a miss probes until it reaches an empty slot. Random names show the cost
 * when the spread doesn't matter. Hits look up distinct {@code String} instances, so {@code equals}
 * runs as it does for keys read from a request or a payload.
 *
 * <p>Compare {@link HashingUtils#hashSpread} against the previous {@code h ^ (h >>> 16)} by running
 * this benchmark on both, base and change alternating fork by fork.
 */
@Fork(3)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Threads(8)
@State(Scope.Benchmark)
public class StringIndexKeyShapeBenchmark {
  @Param({"sequential", "random"})
  public String keys;

  @Param({"64", "1024", "10030"})
  public int size;

  private StringIndex index;
  private String[] hits;
  private String[] misses;

  @Setup
  public void setUp() {
    final String[] names = new String[size];
    hits = new String[size];
    misses = new String[size];
    final Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      names[i] = name(i, random);
      // A distinct instance with the same characters, so equals runs on a hit.
      hits[i] = new String(names[i].toCharArray());
      misses[i] = name(size + i, random);
    }
    index = StringIndex.of(names);
  }

  private String name(final int i, final Random random) {
    if ("sequential".equals(keys)) {
      return String.format(Locale.ROOT, "scale-bulk-%05d", i);
    }
    final char[] chars = new char[16];
    for (int c = 0; c < chars.length; c++) {
      chars[c] = (char) ('a' + random.nextInt(26));
    }
    return new String(chars);
  }

  /** Each thread walks the keys from its own offset. */
  @State(Scope.Thread)
  public static class Cursor {
    private static final AtomicInteger THREADS = new AtomicInteger();

    int next = THREADS.getAndIncrement() * 7919;

    int next(final int size) {
      final int i = Math.floorMod(next, size);
      next = i + 1;
      return i;
    }
  }

  @Benchmark
  public int hit(final Cursor cursor) {
    return index.indexOf(hits[cursor.next(size)]);
  }

  @Benchmark
  public int miss(final Cursor cursor) {
    return index.indexOf(misses[cursor.next(size)]);
  }
}
