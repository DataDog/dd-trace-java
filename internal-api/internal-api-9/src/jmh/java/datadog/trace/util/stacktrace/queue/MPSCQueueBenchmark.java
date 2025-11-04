package datadog.trace.util.stacktrace.queue;

import datadog.trace.util.queue.MpscArrayQueueVarHandle;
import java.util.concurrent.CountDownLatch;
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
MPSCQueueBenchmark.queueTest                1024  thrpt       145.261           ops/us
MPSCQueueBenchmark.queueTest:consume        1024  thrpt        84.185           ops/us
MPSCQueueBenchmark.queueTest:produce        1024  thrpt        61.076           ops/us
MPSCQueueBenchmark.queueTest               65536  thrpt       187.609           ops/us
MPSCQueueBenchmark.queueTest:consume       65536  thrpt       117.097           ops/us
MPSCQueueBenchmark.queueTest:produce       65536  thrpt        70.512           ops/us
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
    CountDownLatch consumerReady;

    @Param({"1024", "65536"})
    int capacity;

    @Setup(Level.Iteration)
    public void setup() {
      queue = new MpscArrayQueueVarHandle<>(capacity);
      consumerReady = new CountDownLatch(1);
    }
  }

  @Benchmark
  @Group("queueTest")
  @GroupThreads(4)
  public void produce(QueueState state) {
    try {
      state.consumerReady.await(); // wait until consumer is ready
    } catch (InterruptedException ignored) {
    }

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
    state.consumerReady.countDown(); // signal producers can start
    Integer v = state.queue.poll();
    if (v != null) {
      bh.consume(v);
    }
  }
}
