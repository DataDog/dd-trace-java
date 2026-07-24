package datadog.trace.bootstrap;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

import com.blogspot.mydailyjava.weaklockfree.WeakConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares the current {@link WeakMapContextStore} (ConcurrentHashMap + inline ReferenceQueue
 * expunge, no cap) against the previous implementation (capped {@link WeakConcurrentMap} with a
 * periodic cleaner), at different levels of concurrency. This is the hot path for every
 * context-store access when field injection is unavailable (issue #10479).
 */
@State(Scope.Benchmark)
@BenchmarkMode(AverageTime)
@OutputTimeUnit(MICROSECONDS)
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
public class WeakMapContextStoreBenchmark {
  private static final int NUM_STORES = 3;
  private static final int NUM_KEYS = 1_000;

  @Param({"old", "new"})
  public String impl;

  private BiFunction[] stores;

  private String[] keys;
  private String[] values;

  private AtomicInteger threadNumber;

  @Setup(Level.Trial)
  public void setup() {
    stores = new BiFunction[NUM_STORES];
    for (int i = 0; i < NUM_STORES; i++) {
      stores[i] =
          "new".equals(impl)
              ? new WeakMapContextStore<>()::putIfAbsent
              : new CappedWeakConcurrentMapStore<>()::putIfAbsent;
    }

    keys = new String[NUM_KEYS];
    values = new String[NUM_KEYS];
    for (int i = 0; i < NUM_KEYS; i++) {
      keys[i] = "key_" + i;
      values[i] = "value_" + i;
    }

    threadNumber = new AtomicInteger();
  }

  @Benchmark
  @Fork(value = 1)
  @Threads(value = 1)
  public void singleThreaded(Blackhole blackhole) {
    test(blackhole);
  }

  @Benchmark
  @Fork(value = 1)
  @Threads(value = 10)
  public void multiThreaded10(Blackhole blackhole) {
    test(blackhole);
  }

  @Benchmark
  @Fork(value = 1)
  @Threads(value = 100)
  public void multiThreaded100(Blackhole blackhole) {
    test(blackhole);
  }

  private void test(Blackhole blackhole) {
    // assign each benchmark thread a single store to operate on during the benchmark;
    // the number of concurrent requests to a store goes up as more threads are added
    BiFunction store = stores[threadNumber.getAndIncrement() % NUM_STORES];
    for (int i = 0; i < NUM_KEYS; i++) {
      blackhole.consume(store.apply(keys[i], values[i]));
    }
  }

  /** The previous implementation: capped WeakConcurrentMap with synchronized putIfAbsent. */
  static final class CappedWeakConcurrentMapStore<K, V> {
    private static final int MAX_SIZE = 50_000;

    private final WeakConcurrentMap<K, V> map = new WeakConcurrentMap<>(false, true);

    V putIfAbsent(final K key, final V context) {
      V existingContext = map.get(key);
      if (null == existingContext) {
        synchronized (map) {
          existingContext = map.get(key);
          if (null == existingContext) {
            existingContext = context;
            if (map.approximateSize() < MAX_SIZE) {
              map.put(key, existingContext);
            }
          }
        }
      }
      return existingContext;
    }
  }
}
