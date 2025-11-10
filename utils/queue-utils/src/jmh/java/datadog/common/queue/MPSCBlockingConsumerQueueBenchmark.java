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
MPSCBlockingConsumerQueueBenchmark.queueTest                       1024  thrpt        237.384          ops/us
MPSCBlockingConsumerQueueBenchmark.queueTest:consume               1024  thrpt        225.826          ops/us
MPSCBlockingConsumerQueueBenchmark.queueTest:produce               1024  thrpt         11.558          ops/us
MPSCBlockingConsumerQueueBenchmark.queueTest                      65536  thrpt        120.258          ops/us
MPSCBlockingConsumerQueueBenchmark.queueTest:consume              65536  thrpt        112.679          ops/us
MPSCBlockingConsumerQueueBenchmark.queueTest:produce              65536  thrpt          7.579          ops/us
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
