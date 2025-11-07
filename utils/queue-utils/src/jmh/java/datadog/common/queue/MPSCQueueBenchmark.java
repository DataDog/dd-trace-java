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
Benchmark                             (capacity)   Mode  Cnt    Score   Error   Units
MPSCQueueBenchmark.queueTest                1024  thrpt       272.751          ops/us
MPSCQueueBenchmark.queueTest:consume        1024  thrpt       258.737          ops/us
MPSCQueueBenchmark.queueTest:produce        1024  thrpt        14.013          ops/us
MPSCQueueBenchmark.queueTest:·async         1024  thrpt           NaN             ---
MPSCQueueBenchmark.queueTest               65536  thrpt       120.776          ops/us
MPSCQueueBenchmark.queueTest:consume       65536  thrpt       108.595          ops/us
MPSCQueueBenchmark.queueTest:produce       65536  thrpt        12.182          ops/us
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 1, time = 30)
@Measurement(iterations = 1, time = 30)
@Fork(1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class MPSCQueueBenchmark {
  @State(Scope.Group)
  public static class QueueState {
    MpscArrayQueueVarHandle<Integer> queue;

    @Param({"1024", "65536"})
    int capacity;

    @Setup(Level.Iteration)
    public void setup() {
      queue = new MpscArrayQueueVarHandle<>(capacity);
    }
  }

  @Benchmark
  @Group("queueTest")
  @GroupThreads(4)
  public void produce(QueueState state, Blackhole blackhole) {
    blackhole.consume(state.queue.offer(0));
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
