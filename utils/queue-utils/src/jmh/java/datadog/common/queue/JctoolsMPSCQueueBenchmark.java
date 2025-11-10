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
Benchmark                             (capacity)   Mode  Cnt    Score   Error   Units
JctoolsMPSCQueueBenchmark.queueTest                                1024  thrpt         29.444          ops/us
JctoolsMPSCQueueBenchmark.queueTest:consume                        1024  thrpt         21.230          ops/us
JctoolsMPSCQueueBenchmark.queueTest:produce                        1024  thrpt          8.214          ops/us
JctoolsMPSCQueueBenchmark.queueTest                               65536  thrpt         30.218          ops/us
JctoolsMPSCQueueBenchmark.queueTest:consume                       65536  thrpt         22.846          ops/us
JctoolsMPSCQueueBenchmark.queueTest:produce                       65536  thrpt          7.372          ops/us
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 1, time = 30)
@Measurement(iterations = 1, time = 30)
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
