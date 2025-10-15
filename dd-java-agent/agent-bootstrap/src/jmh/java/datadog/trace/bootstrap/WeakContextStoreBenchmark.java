package datadog.trace.bootstrap;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

import datadog.trace.bootstrap.weakmap.WeakMapContextStore;
import datadog.trace.bootstrap.weakmap.WeakMaps;
import java.util.HashMap;
import java.util.Map;
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

@State(Scope.Benchmark)
@BenchmarkMode(AverageTime)
@OutputTimeUnit(MICROSECONDS)
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
public class WeakContextStoreBenchmark {
  private static final int NUM_STORES = 3;

  @Param({"false", "true"})
  public boolean global;

  private BiFunction[] stores;

  private Map<String, String> kvs;

  private AtomicInteger threadNumber;

  @Setup(Level.Trial)
  public void setup() {
    WeakMaps.registerAsSupplier();

    stores = new BiFunction[NUM_STORES];
    for (int i = 0; i < NUM_STORES; i++) {
      stores[i] = global ? globalWeakStorePutIfAbsent(i) : weakMapStorePutIfAbsent(i);
    }

    kvs = new HashMap<>();
    for (int i = 0; i < 1_000; i++) {
      kvs.put("key_" + i, "value_" + i);
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
    // assign each benchmark thread a single store to operate on during the benchmark
    // the number of concurrent requests to a store goes up as more threads are added
    BiFunction store = stores[threadNumber.getAndIncrement() % NUM_STORES];
    for (Map.Entry e : kvs.entrySet()) {
      blackhole.consume(store.apply(e.getKey(), e.getValue()));
    }
  }

  private static BiFunction globalWeakStorePutIfAbsent(int storeId) {
    return (k, v) -> GlobalWeakContextStore.weakPutIfAbsent(k, storeId, v);
  }

  private static BiFunction weakMapStorePutIfAbsent(int storeId) {
    return new WeakMapContextStore<>(storeId)::putIfAbsent;
  }
}
