package datadog.common.queue;

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
Benchmark                                             (capacity)   Mode  Cnt    Score   Error   Units
MPSCBlockingConsumerQueueBenchmark.queueTest                1024  thrpt       121.534          ops/us
MPSCBlockingConsumerQueueBenchmark.queueTest:async          1024  thrpt           NaN             ---
MPSCBlockingConsumerQueueBenchmark.queueTest:consume        1024  thrpt       110.962          ops/us
MPSCBlockingConsumerQueueBenchmark.queueTest:produce        1024  thrpt        10.572          ops/us
MPSCBlockingConsumerQueueBenchmark.queueTest               65536  thrpt       126.856          ops/us
MPSCBlockingConsumerQueueBenchmark.queueTest:async         65536  thrpt           NaN             ---
MPSCBlockingConsumerQueueBenchmark.queueTest:consume       65536  thrpt       113.213          ops/us
MPSCBlockingConsumerQueueBenchmark.queueTest:produce       65536  thrpt        13.644          ops/us
*/
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 1, time = 30)
@Measurement(iterations = 1, time = 30)
@Fork(1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class MPSCBlockingConsumerQueueBenchmark {
  @State(Scope.Group)
  public static class QueueState {
    MpscBlockingConsumerArrayQueueVarHandle<Integer> queue;
    CountDownLatch consumerReady;

    @Param({"1024", "65536"})
    int capacity;

    @Setup(Level.Iteration)
    public void setup() {
      queue = new MpscBlockingConsumerArrayQueueVarHandle<>(capacity);
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
