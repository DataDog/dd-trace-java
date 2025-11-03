package datadog.trace.util.queue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MpscArrayQueue;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/*
Benchmark                                     (capacity)   Mode  Cnt   Score   Error   Units
JcToolsdMPSCQueueBenchmark.queueTest                1024  thrpt       75.207          ops/us
JcToolsdMPSCQueueBenchmark.queueTest:consume        1024  thrpt       62.553          ops/us
JcToolsdMPSCQueueBenchmark.queueTest:produce        1024  thrpt       12.654          ops/us
JcToolsdMPSCQueueBenchmark.queueTest               65536  thrpt       36.381          ops/us
JcToolsdMPSCQueueBenchmark.queueTest:consume       65536  thrpt       22.665          ops/us
JcToolsdMPSCQueueBenchmark.queueTest:produce       65536  thrpt       13.717          ops/us
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 1, time = 30)
@Measurement(iterations = 1, time = 30)
@Threads(Threads.MAX)
@Fork(1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class JcToolsMPSCQueueBenchmark {
  @State(Scope.Group)
  public static class QueueState {
    MpscArrayQueue<Integer> queue;
    CountDownLatch consumerReady;

    @Param({"1024", "65536"})
    int capacity;

    @Setup(Level.Iteration)
    public void setup() {
      queue = new MpscArrayQueue<>(capacity);
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
