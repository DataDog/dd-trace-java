package com.datadog.openfeature;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.ref.Reference;
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
 * ufc-config.json}. Work that moves into the parser, such as indexes, compiled patterns or prebuilt
 * JSON values, shows here.
 *
 * <p>The setup also prints the heap that a parsed configuration retains. It is the used heap after
 * a full collection, with and without the parsed configuration, as the smallest of three
 * measurements, so it is approximate.
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
  @SuppressForbidden
  public void setUp() throws IOException {
    config = ScaleSuites.load(suite).config();
    // The first parse initializes the parser, so it is left out of the measurement.
    ServerConfiguration retained = UniversalFlagConfigParser.parse(config);
    long retainedBytes = Long.MAX_VALUE;
    for (int i = 0; i < 3; i++) {
      retained = null;
      final long before = usedHeapAfterCollection();
      retained = UniversalFlagConfigParser.parse(config);
      final long after = usedHeapAfterCollection();
      Reference.reachabilityFence(retained);
      retainedBytes = Math.min(retainedBytes, after - before);
    }
    System.out.println(
        "["
            + suite
            + "] config "
            + config.length / 1024
            + " KB, "
            + retained.flags.size()
            + " flags, retained heap about "
            + retainedBytes / 1024
            + " KB");
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
