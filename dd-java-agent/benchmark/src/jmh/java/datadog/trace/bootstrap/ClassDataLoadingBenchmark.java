package datadog.trace.bootstrap;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Measures the archive-reading portion of loading the classes commonly needed before main. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
@Fork(1)
public class ClassDataLoadingBenchmark {
  @State(Scope.Thread)
  public static class BenchmarkState {
    @Param({
      "baseline",
      "production",
      "stored-10",
      "stored-25",
      "stored-50",
      "stored-75",
      "stored-100",
      "packed-64",
      "packed-64-dedup",
      "packed-256",
      "packed-1024",
      "packed-all"
    })
    public String layout;

    @Param({"25", "50", "100"})
    public int percentage;

    private URL jarUrl;
    private List<String> commonClasses;

    @Setup(Level.Trial)
    public void prepareTrial() throws Exception {
      File benchmarkDir =
          new File(
              System.getProperty("datadog.classdata.benchmark.dir", "build/classdata-benchmark"));
      jarUrl = new File(benchmarkDir, layout + ".jar").toURI().toURL();
      commonClasses =
          Files.readAllLines(
              new File(benchmarkDir, "common-classes.txt").toPath(), StandardCharsets.UTF_8);
    }

    private static ClassLoader platformClassLoader() {
      ClassLoader system = ClassLoader.getSystemClassLoader();
      ClassLoader parent = system.getParent();
      return parent == null ? system : parent;
    }
  }

  @State(Scope.Thread)
  public static class OpenLoaderState {
    private DatadogClassLoader loader;

    @Setup(Level.Invocation)
    public void prepareInvocation(BenchmarkState state) throws Exception {
      loader = new DatadogClassLoader(state.jarUrl, BenchmarkState.platformClassLoader());
    }

    @TearDown(Level.Invocation)
    public void closeInvocation() throws Exception {
      loader.close();
    }
  }

  @Benchmark
  public void readCommonClasses(
      BenchmarkState state, OpenLoaderState loaderState, Blackhole blackhole) throws Exception {
    readClasses(loaderState.loader, state.commonClasses, state.percentage, blackhole);
  }

  @Benchmark
  public void openAndReadCommonClasses(BenchmarkState state, Blackhole blackhole) throws Exception {
    DatadogClassLoader loader = new DatadogClassLoader(state.jarUrl, state.platformClassLoader());
    try {
      readClasses(loader, state.commonClasses, state.percentage, blackhole);
    } finally {
      loader.close();
    }
  }

  @Benchmark
  public void openAndDefineCommonClasses(BenchmarkState state, Blackhole blackhole)
      throws Exception {
    DatadogClassLoader loader = new DatadogClassLoader(state.jarUrl, state.platformClassLoader());
    try {
      int count = percentageCount(state.commonClasses.size(), state.percentage);
      for (int i = 0; i < count; i++) {
        blackhole.consume(loader.loadClass(state.commonClasses.get(i)));
      }
      blackhole.consume(loader.retainedPackedClassBytes());
    } finally {
      loader.close();
    }
  }

  private static void readClasses(
      DatadogClassLoader loader, List<String> commonClasses, int percentage, Blackhole blackhole)
      throws Exception {
    int count = percentageCount(commonClasses.size(), percentage);
    long bytes = 0;
    for (int i = 0; i < count; i++) {
      byte[] classData = loader.loadClassBytes(commonClasses.get(i));
      bytes += classData.length;
      blackhole.consume(classData);
    }
    blackhole.consume(bytes);
  }

  private static int percentageCount(int size, int percentage) {
    return Math.max(1, (size * percentage + 99) / 100);
  }
}
