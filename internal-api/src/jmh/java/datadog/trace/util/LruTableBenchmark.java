package datadog.trace.util;

import java.util.LinkedHashMap;
import java.util.Map;
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
 * A bounded LRU keyed by two strings, as a (flag, subject) exposure cache is: {@link LruTable.D2}
 * against a {@code LinkedHashMap} in access order with {@code removeEldestEntry}, which needs a key
 * object per lookup and a node per entry.
 *
 * <p>Each operation looks up one key and, on a miss, inserts it, evicting the least recently used
 * entry once the table is full.
 *
 * <ul>
 *   <li>{@code fits}: the keys are half the bound, so after warmup every operation is a hit that
 *       moves the entry to the most recent end.
 *   <li>{@code overflows}: the keys are four times the bound and visited in shuffled order, so most
 *       operations are a miss, an insert and an eviction.
 * </ul>
 *
 * <p>Tables are per thread, as a lock would make them; the threads only add realistic cache and
 * memory pressure. Report time and allocation ({@code -prof gc}).
 */
@Fork(3)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Threads(4)
@State(Scope.Benchmark)
public class LruTableBenchmark {
  private static final int FLAGS = 8;

  @Param({"1024", "65536"})
  public int bound;

  @Param({"fits", "overflows"})
  public String workingSet;

  String[] flags;
  String[] subjects;
  int[] order;

  @Setup
  public void setUp() {
    final int keys = "fits".equals(workingSet) ? bound / 2 : bound * 4;
    flags = new String[FLAGS];
    for (int i = 0; i < FLAGS; i++) {
      flags[i] = "flag-" + i;
    }
    subjects = new String[keys];
    for (int i = 0; i < keys; i++) {
      subjects[i] = "subject-" + Integer.toHexString(i * 0x9E3779B9);
    }
    order = new int[keys];
    for (int i = 0; i < keys; i++) {
      order[i] = i;
    }
    final Random random = new Random(42);
    for (int i = keys - 1; i > 0; i--) {
      final int j = random.nextInt(i + 1);
      final int t = order[i];
      order[i] = order[j];
      order[j] = t;
    }
  }

  static final class Exposure extends LruTable.D2.Entry<String, String> {
    String variant;

    Exposure(final String flag, final String subject, final String variant) {
      super(flag, subject);
      this.variant = variant;
    }
  }

  static final class Key {
    final String flag;
    final String subject;

    Key(final String flag, final String subject) {
      this.flag = flag;
      this.subject = subject;
    }

    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof Key)) {
        return false;
      }
      final Key other = (Key) o;
      return flag.equals(other.flag) && subject.equals(other.subject);
    }

    @Override
    public int hashCode() {
      // Not Objects.hash, whose varargs array would charge the map for an avoidable allocation.
      return 31 * flag.hashCode() + subject.hashCode();
    }
  }

  /** Per-thread tables and a cursor that starts each thread at its own offset. */
  @State(Scope.Thread)
  public static class Tables {
    private static final AtomicInteger THREADS = new AtomicInteger();

    LruTable.D2<String, String, Exposure> lruTable;
    LinkedHashMap<Key, String> linkedHashMap;
    int next;

    @Setup
    public void setUp(final LruTableBenchmark benchmark) {
      final int bound = benchmark.bound;
      lruTable = new LruTable.D2<>(bound);
      linkedHashMap =
          new LinkedHashMap<Key, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<Key, String> eldest) {
              return size() > bound;
            }
          };
      next = THREADS.getAndIncrement() * 7919;
    }

    int next(final int length) {
      final int i = Math.floorMod(next, length);
      next = i + 1;
      return i;
    }
  }

  @Benchmark
  public Object lruTable(final Tables tables) {
    final int i = order[tables.next(order.length)];
    final String flag = flags[i % FLAGS];
    final String subject = subjects[i];
    final Exposure exposure = tables.lruTable.get(flag, subject);
    if (exposure != null) {
      return exposure;
    }
    final Exposure created = new Exposure(flag, subject, "on");
    tables.lruTable.insert(created);
    return created;
  }

  @Benchmark
  public Object linkedHashMap(final Tables tables) {
    final int i = order[tables.next(order.length)];
    final String flag = flags[i % FLAGS];
    final String subject = subjects[i];
    final String variant = tables.linkedHashMap.get(new Key(flag, subject));
    if (variant != null) {
      return variant;
    }
    tables.linkedHashMap.put(new Key(flag, subject), "on");
    return flag;
  }
}
