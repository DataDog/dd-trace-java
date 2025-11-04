package datadog.trace.util.stacktrace.queue;

import datadog.trace.util.queue.SpscArrayQueueVarHandle;
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
SPSCQueueBenchmark.queueTest                1024  thrpt        91.112           ops/us
SPSCQueueBenchmark.queueTest:consume        1024  thrpt        52.640           ops/us
SPSCQueueBenchmark.queueTest:produce        1024  thrpt        38.472           ops/us
SPSCQueueBenchmark.queueTest               65536  thrpt       140.663           ops/us
SPSCQueueBenchmark.queueTest:consume       65536  thrpt        70.363           ops/us
SPSCQueueBenchmark.queueTest:produce       65536  thrpt        70.300           ops/us
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 1, time = 30)
@Measurement(iterations = 1, time = 30)
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
  public void produce(QueueState state) {

    // bounded attempt: try once, then yield if full
    boolean offered = state.queue.offer(0);
    if (!offered) {
      Thread.yield();
    }
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
