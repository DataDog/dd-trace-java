package datadog.trace.util.stacktrace.queue;

import datadog.trace.util.queue.SpmcArrayQueueVarHandle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/*
SPMCQueueBenchmark.spmc                      N/A  thrpt    5  484.103 ± 64.709  ops/us
SPMCQueueBenchmark.spmc:consumer             N/A  thrpt    5  466.954 ± 65.712  ops/us
SPMCQueueBenchmark.spmc:producer             N/A  thrpt    5   17.149 ±  1.541  ops/us
 */
@BenchmarkMode(Mode.Throughput)
@State(Scope.Group)
@Fork(value = 1, warmups = 0)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SPMCQueueBenchmark {

  private static final int QUEUE_CAPACITY = 1024;
  private static final int ITEMS_TO_PRODUCE = 100_000;

  private SpmcArrayQueueVarHandle<Integer> queue;
  private AtomicInteger produced;
  private AtomicInteger consumed;

  @Setup(Level.Iteration)
  public void setup() {
    queue = new SpmcArrayQueueVarHandle<>(QUEUE_CAPACITY);
    produced = new AtomicInteger(0);
    consumed = new AtomicInteger(0);

    // Pre-fill queue for warmup safety
    int warmupFill = Math.min(QUEUE_CAPACITY / 2, ITEMS_TO_PRODUCE);
    for (int i = 0; i < warmupFill; i++) {
      queue.offer(i);
      produced.incrementAndGet();
    }
  }

  // Single producer in the group
  @Benchmark
  @Group("spmc")
  @GroupThreads(1)
  public void producer() {
    int i = produced.getAndIncrement();
    if (i < ITEMS_TO_PRODUCE) {
      while (!queue.offer(i)) {
        LockSupport.parkNanos(1L);
      }
    }
  }

  // Multiple consumers in the group
  @Benchmark
  @Group("spmc")
  @GroupThreads(4) // adjust number of consumers
  public int consumer() {
    while (true) {
      Integer val = queue.poll();
      if (val != null) {
        consumed.incrementAndGet();
        return val;
      }

      if (produced.get() >= ITEMS_TO_PRODUCE && queue.isEmpty()) {
        return 0;
      }

      LockSupport.parkNanos(1L);
    }
  }
}
