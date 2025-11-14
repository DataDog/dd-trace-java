package datadog.common.queue;

import java.util.concurrent.TimeUnit;
import org.jctools.queues.SpscArrayQueue;
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
Benchmark                                                    (capacity)   Mode  Cnt    Score   Error   Units
JctoolsSPSCQueueBenchmark.queueTest                                1024  thrpt       259,418          ops/us
JctoolsSPSCQueueBenchmark.queueTest:consume                        1024  thrpt       129,694          ops/us
JctoolsSPSCQueueBenchmark.queueTest:produce                        1024  thrpt       129,724          ops/us
JctoolsSPSCQueueBenchmark.queueTest                               65536  thrpt       537,111          ops/us
JctoolsSPSCQueueBenchmark.queueTest:consume                       65536  thrpt       268,577          ops/us
JctoolsSPSCQueueBenchmark.queueTest:produce                       65536  thrpt       268,534          ops/us
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 10)
@Measurement(iterations = 1, time = 30)
@Fork(1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class JctoolsSPSCQueueBenchmark {
  @State(Scope.Group)
  public static class QueueState {
    SpscArrayQueue<Integer> queue;

    @Param({"1024", "65536"})
    int capacity;

    @Setup(Level.Iteration)
    public void setup() {
      queue = new SpscArrayQueue<>(capacity);
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
