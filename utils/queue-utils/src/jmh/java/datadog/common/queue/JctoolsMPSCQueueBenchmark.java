package datadog.common.queue;

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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/*
Benchmark                                                    (capacity)   Mode  Cnt    Score     Error   Units
JctoolsMPSCQueueBenchmark.queueTest                                1024  thrpt    3   26,429 ±  24,149  ops/us
JctoolsMPSCQueueBenchmark.queueTest:consume                        1024  thrpt    3   17,510 ±  23,328  ops/us
JctoolsMPSCQueueBenchmark.queueTest:produce                        1024  thrpt    3    8,919 ±   3,046  ops/us
JctoolsMPSCQueueBenchmark.queueTest                               65536  thrpt    3   24,071 ±  71,594  ops/us
JctoolsMPSCQueueBenchmark.queueTest:consume                       65536  thrpt    3   16,710 ±  88,477  ops/us
JctoolsMPSCQueueBenchmark.queueTest:produce                       65536  thrpt    3    7,361 ±  18,533  ops/us
*/
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 10)
@Measurement(iterations = 3, time = 10)
@Fork(1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class JctoolsMPSCQueueBenchmark {
  @State(Scope.Group)
  public static class QueueState {
    MpscArrayQueue<Integer> queue;

    @Param({"1024", "65536"})
    int capacity;

    @Setup(Level.Iteration)
    public void setup() {
      queue = new MpscArrayQueue<>(capacity);
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
