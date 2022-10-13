package datadog.trace.agent.tooling.bytebuddy.csi;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.function.Function;
import datadog.trace.bootstrap.Agent;
import java.lang.instrument.Instrumentation;
import java.net.URLClassLoader;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Warmup(iterations = CallSiteTypeBenchmark.WARMUPS)
@Measurement(iterations = CallSiteTypeBenchmark.MEASUREMENTS)
@OperationsPerInvocation(CallSiteTypeBenchmark.OPERATIONS)
public class CallSiteTypeBenchmark {

  public static final int FORKS = 3;
  public static final int OPERATIONS = 1000;
  public static final int WARMUPS = 2;
  public static final int MEASUREMENTS = 5;

  private Function<String, String> stringBuilderFunction;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    final URLClassLoader parent = (URLClassLoader) getClass().getClassLoader();
    final Instrumentation instrumentation = ByteBuddyAgent.install();
    Agent.start(instrumentation, Agent.class.getProtectionDomain().getCodeSource().getLocation());
    final ClassLoader classLoader = new URLClassLoader(parent.getURLs());
    stringBuilderFunction = loadClass("foo.bar.StringBuilderInsert", classLoader);
  }

  @SuppressWarnings("unchecked")
  private Function<String, String> loadClass(final String name, final ClassLoader classLoader)
      throws ClassNotFoundException, InstantiationException, IllegalAccessException {
    final Class<?> clazz = Class.forName(name, true, classLoader);
    return (Function<String, String>) clazz.newInstance();
  }

  @Benchmark
  @Fork(
      value = FORKS,
      jvmArgsAppend = {
        "-Ddd.trace.enabled=false",
        "-Ddd.trace.startup.logs=false",
        "-Ddd.jmxfetch.enabled=false"
      })
  public void noneArray(final Blackhole blackhole) {
    runBenchmark(false, blackhole);
  }

  @Benchmark
  @Fork(
      value = FORKS,
      jvmArgsAppend = {
        "-Ddd.trace.enabled=false",
        "-Ddd.trace.startup.logs=false",
        "-Ddd.jmxfetch.enabled=false",
        "-Ddd.benchmark.instrumentation=beforeArray"
      })
  public void beforeArray(final Blackhole blackhole) {
    runBenchmark(true, blackhole);
  }

  @Benchmark
  @Fork(
      value = FORKS,
      jvmArgsAppend = {
        "-Ddd.trace.enabled=false",
        "-Ddd.trace.startup.logs=false",
        "-Ddd.jmxfetch.enabled=false",
        "-Ddd.benchmark.instrumentation=aroundArray"
      })
  public void aroundArray(final Blackhole blackhole) {
    runBenchmark(true, blackhole);
  }

  @Benchmark
  @Fork(
      value = FORKS,
      jvmArgsAppend = {
        "-Ddd.trace.enabled=false",
        "-Ddd.trace.startup.logs=false",
        "-Ddd.jmxfetch.enabled=false",
        "-Ddd.benchmark.instrumentation=afterArray"
      })
  public void afterArray(final Blackhole blackhole) {
    runBenchmark(true, blackhole);
  }

  @Benchmark
  @Fork(
      value = FORKS,
      jvmArgsAppend = {
        "-Ddd.trace.enabled=false",
        "-Ddd.trace.startup.logs=false",
        "-Ddd.jmxfetch.enabled=false"
      })
  public void noneStack(final Blackhole blackhole) {
    runBenchmark(false, blackhole);
  }

  @Benchmark
  @Fork(
      value = FORKS,
      jvmArgsAppend = {
        "-Ddd.trace.enabled=false",
        "-Ddd.trace.startup.logs=false",
        "-Ddd.jmxfetch.enabled=false",
        "-Ddd.benchmark.instrumentation=beforeStack"
      })
  public void beforeStack(final Blackhole blackhole) {
    runBenchmark(true, blackhole);
  }

  @Benchmark
  @Fork(
      value = FORKS,
      jvmArgsAppend = {
        "-Ddd.trace.enabled=false",
        "-Ddd.trace.startup.logs=false",
        "-Ddd.jmxfetch.enabled=false",
        "-Ddd.benchmark.instrumentation=aroundStack"
      })
  public void aroundStack(final Blackhole blackhole) {
    runBenchmark(true, blackhole);
  }

  @Benchmark
  @Fork(
      value = FORKS,
      jvmArgsAppend = {
        "-Ddd.trace.enabled=false",
        "-Ddd.trace.startup.logs=false",
        "-Ddd.jmxfetch.enabled=false",
        "-Ddd.benchmark.instrumentation=afterStack"
      })
  public void afterStack(final Blackhole blackhole) {
    runBenchmark(true, blackhole);
  }

  private void runBenchmark(final boolean transform, final Blackhole blackhole) {
    assertBenchmark(transform);
    for (int i = 0; i < OPERATIONS - 1; i++) {
      blackhole.consume(stringBuilderFunction.apply("Hello!"));
    }
  }

  private void assertBenchmark(final boolean transform) {
    final String resultCheck = stringBuilderFunction.apply("Hello!");
    if (resultCheck == null) {
      throw new RuntimeException("Empty result received");
    }
    final String expected = !transform ? "Hello!" : "Hello! [Transformed]";
    if (!expected.equals(resultCheck)) {
      throw new RuntimeException(
          String.format("Wrong result, expected '%s' but received '%s'", expected, resultCheck));
    }
  }
}
