package datadog.trace.util.queue;

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
Benchmark                             (capacity)   Mode  Cnt    Score   Error   Units
SPSCQueueBenchmark.queueTest                1024  thrpt       136.138          ops/us
SPSCQueueBenchmark.queueTest:consume        1024  thrpt        68.767          ops/us
SPSCQueueBenchmark.queueTest:produce        1024  thrpt        67.371          ops/us
SPSCQueueBenchmark.queueTest               65536  thrpt       127.357          ops/us
SPSCQueueBenchmark.queueTest:consume       65536  thrpt        65.933          ops/us
SPSCQueueBenchmark.queueTest:produce       65536  thrpt        61.424          ops/us
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 1, time = 30)
@Measurement(iterations = 1, time = 30)
@Fork(1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class JcToolsSPSCQueueBenchmark {
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
