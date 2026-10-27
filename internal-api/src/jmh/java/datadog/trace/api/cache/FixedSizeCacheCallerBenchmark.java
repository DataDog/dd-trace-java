package datadog.trace.api.cache;

import datadog.trace.api.normalize.HttpResourceNames;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Measures real HTTP resource joining, including lookup-key creation and cache misses. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(3)
public class FixedSizeCacheCallerBenchmark {
  @Param({"16", "512"})
  public int cardinality;

  private String[] methods;
  private String[] paths;
  private int cursor;

  @Setup
  public void setup() {
    methods = new String[cardinality];
    paths = new String[cardinality];
    String[] verbs = {"get", "POST", "patch", "HEAD"};
    for (int i = 0; i < cardinality; i++) {
      methods[i] = new String(verbs[i & 3]);
      switch (i & 3) {
        case 0:
          paths[i] = "/owners/" + i + "/pets";
          break;
        case 1:
          paths[i] = "/vets/" + i;
          break;
        case 2:
          paths[i] = "/caf\u00e9/" + i;
          break;
        default:
          paths[i] = "/owners/" + i + "/";
      }
    }
  }

  @Benchmark
  public CharSequence join() {
    int index = (cursor++ * 13) & (cardinality - 1);
    return HttpResourceNames.join(methods[index], paths[index]);
  }
}
