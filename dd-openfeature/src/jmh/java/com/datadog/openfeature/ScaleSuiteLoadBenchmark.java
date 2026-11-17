package com.datadog.openfeature;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Configuration load cost of the opt-in scale suites: the time to parse each suite's {@code
 * ufc-config.json}. Work that an evaluator moves to load time, such as indexes, compiled patterns
 * or prebuilt JSON values, shows here.
 *
 * <p>The setup also prints the heap that a parsed configuration retains. It is the used heap after
 * a full collection, with and without the parsed configuration, so it is approximate.
 *
 * <p>Run: {@code ./gradlew :dd-openfeature:jmh -Pjmh.includes=ScaleSuiteLoadBenchmark
 * -Pjmh.profilers=gc}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MILLISECONDS)
@Fork(value = 1)
public class ScaleSuiteLoadBenchmark {

  @Param({"scale", "scale-json"})
  public String suite;

  private byte[] config;

  @Setup(Level.Trial)
  public void setUp() throws IOException {
    config = ScaleSuites.load(suite).config();
    final long before = usedHeapAfterCollection();
    ServerConfiguration retained = UniversalFlagConfigParser.parse(config);
    final long after = usedHeapAfterCollection();
    System.out.println(
        "["
            + suite
            + "] config "
            + config.length / 1024
            + " KB, "
            + retained.flags.size()
            + " flags, retained heap about "
            + Math.max(0, after - before) / 1024
            + " KB");
    retained = null;
  }

  @Benchmark
  public ServerConfiguration parse() throws IOException {
    return UniversalFlagConfigParser.parse(config);
  }

  private static long usedHeapAfterCollection() {
    final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    for (int i = 0; i < 3; i++) {
      System.gc();
    }
    return memory.getHeapMemoryUsage().getUsed();
  }
}
