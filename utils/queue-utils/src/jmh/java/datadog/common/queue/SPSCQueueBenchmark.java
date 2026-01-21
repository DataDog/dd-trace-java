package datadog.common.queue;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/*
Benchmark                                                    (capacity)   Mode  Cnt    Score     Error   Units
SPSCQueueBenchmark.queueTest                                       1024  thrpt    3  180.354 ± 223.400  ops/us
SPSCQueueBenchmark.queueTest:consume                               1024  thrpt    3   90,544 ± 155,265  ops/us
SPSCQueueBenchmark.queueTest:produce                               1024  thrpt    3   89,810 ± 160,517  ops/us
SPSCQueueBenchmark.queueTest                                      65536  thrpt    3  449,770 ±   4,365  ops/us
SPSCQueueBenchmark.queueTest:consume                              65536  thrpt    3  224,937 ±   1,432  ops/us
SPSCQueueBenchmark.queueTest:produce                              65536  thrpt    3  224,833 ±   2,992  ops/us
*/
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 10)
@Measurement(iterations = 3, time = 10)
@Fork(1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class SPSCQueueBenchmark {
  @State(Scope.Group)
  public static class QueueState {
    SpscArrayQueueVarHandle<Integer> queue;

    @Param({"1024", "65536"})
    int capacity;

    @Setup(Level.Iteration)
    public void setup() {
      queue = new SpscArrayQueueVarHandle<>(capacity);
    }
  }

  @Benchmark
  @Group("queueTest")
  @GroupThreads(1)
  public void produce(QueueState state, Blackhole bh) {
    bh.consume(state.queue.offer(0));
  }

  @Benchmark
  @Group("queueTest")
  @GroupThreads(1)
  public void consume(QueueState state, Blackhole bh) {
    Integer v = state.queue.poll();
    if (v != null) {
      bh.consume(v);
    }
  }
}
