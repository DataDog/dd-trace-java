package datadog.trace.util.queue;

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
Benchmark                          Mode  Cnt    Score   Error   Units
SPMCQueueBenchmark.spmc           thrpt    5  266.576 ± 9.589  ops/us
SPMCQueueBenchmark.spmc:consumer  thrpt    5  250.901 ± 9.383  ops/us
SPMCQueueBenchmark.spmc:producer  thrpt    5   15.675 ± 0.507  ops/us
 */
@BenchmarkMode(Mode.Throughput)
@State(Scope.Group)
@Fork(value = 1, warmups = 0)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SPMCQueueBenchmark {

  private static final int QUEUE_CAPACITY = 1024;
  private static final int ITEMS_TO_PRODUCE = 100_000;

  private SpmcArrayQueue<Integer> queue;
  private AtomicInteger produced;
  private AtomicInteger consumed;

  @Setup(Level.Iteration)
  public void setup() {
    queue = new SpmcArrayQueue<>(QUEUE_CAPACITY);
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
