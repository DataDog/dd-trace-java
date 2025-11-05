package datadog.trace.common;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.concurrent.ConcurrentHashMap;
import org.jctools.maps.NonBlockingHashMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/*
JDK 1.8
Benchmark                                            Mode  Cnt  Score   Error  Units
NonBlockingHashMapBenchmark.benchConcurrentHashMap   avgt       1.153          us/op
NonBlockingHashMapBenchmark.benchNonBlockingHashMap  avgt       1.457          us/op

JDK 21
Benchmark                                            Mode  Cnt  Score   Error  Units
NonBlockingHashMapBenchmark.benchConcurrentHashMap   avgt       1.088          us/op
NonBlockingHashMapBenchmark.benchNonBlockingHashMap  avgt       1.278          us/op
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 30, timeUnit = SECONDS)
@Measurement(iterations = 1, time = 30, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@SuppressForbidden
public class NonBlockingHashMapBenchmark {
  private NonBlockingHashMap nonBlockingHashMap;
  private ConcurrentHashMap concurrentHashMap;

  @Setup(Level.Iteration)
  public void setup() {
    nonBlockingHashMap = new NonBlockingHashMap(512);
    concurrentHashMap = new ConcurrentHashMap(512);
    for (int i = 0; i < 256; i++) {
      nonBlockingHashMap.put("test" + i, "test");
      concurrentHashMap.put("test" + i, "test");
    }
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void benchNonBlockingHashMap(Blackhole blackhole) {
    nonBlockingHashMap.put("test", "test");
    blackhole.consume(nonBlockingHashMap.remove("test"));
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void benchConcurrentHashMap(Blackhole blackhole) {
    concurrentHashMap.put("test", "test");
    blackhole.consume(concurrentHashMap.remove("test"));
  }
}
