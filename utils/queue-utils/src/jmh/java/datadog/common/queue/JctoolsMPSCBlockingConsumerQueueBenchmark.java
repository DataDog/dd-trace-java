package datadog.common.queue;

import java.util.concurrent.TimeUnit;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
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
JctoolsMPSCBlockingConsumerQueueBenchmark.queueTest                1024  thrpt    3   31,150 ± 211,089  ops/us
JctoolsMPSCBlockingConsumerQueueBenchmark.queueTest:consume        1024  thrpt    3   23,906 ± 215,572  ops/us
JctoolsMPSCBlockingConsumerQueueBenchmark.queueTest:produce        1024  thrpt    3    7,244 ±   9,645  ops/us
JctoolsMPSCBlockingConsumerQueueBenchmark.queueTest               65536  thrpt    3   27,277 ±  69,378  ops/us
JctoolsMPSCBlockingConsumerQueueBenchmark.queueTest:consume       65536  thrpt    3   21,053 ±  64,255  ops/us
JctoolsMPSCBlockingConsumerQueueBenchmark.queueTest:produce       65536  thrpt    3    6,224 ±  11,748  ops/us
*/
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 10)
@Measurement(iterations = 3, time = 10)
@Fork(1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class JctoolsMPSCBlockingConsumerQueueBenchmark {
  @State(Scope.Group)
  public static class QueueState {
    MpscBlockingConsumerArrayQueue<Integer> queue;

    @Param({"1024", "65536"})
    int capacity;

    @Setup(Level.Iteration)
    public void setup() {
      queue = new MpscBlockingConsumerArrayQueue<>(capacity);
    }
  }

  @Benchmark
  @Group("queueTest")
  @GroupThreads(4)
  public void produce(QueueState state, Blackhole bh) {
    bh.consume(state.queue.offer(1));
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
