package datadog.trace.agent.tooling.bytebuddy.csi;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.bootstrap.Agent;
import java.lang.instrument.Instrumentation;
import java.net.URLClassLoader;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestTemplate;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(MILLISECONDS)
public class CallSiteBenchmark {

  public static final int FORKS = 10;

  private ClassLoader classLoader;

  private Instrumentation instrumentation;

  @Setup(Level.Trial)
  public void setUp() {
    final URLClassLoader parent = (URLClassLoader) getClass().getClassLoader();
    classLoader = new URLClassLoader(parent.getURLs());
    instrumentation = ByteBuddyAgent.install();
  }

  @Benchmark
  @Fork(
      value = FORKS,
      jvmArgsAppend = {
        "-Ddd.trace.enabled=false",
        "-Ddd.trace.startup.logs=false",
        "-Ddd.jmxfetch.enabled=false"
      })
  public void none() throws Exception {
    runBenchmark(false);
  }

  @Benchmark
  @Fork(
      value = FORKS,
      jvmArgsAppend = {
        "-Ddd.trace.enabled=false",
        "-Ddd.trace.startup.logs=false",
        "-Ddd.jmxfetch.enabled=false",
        "-Ddd.benchmark.instrumentation=callee"
      })
  public void callee() throws Exception {
    runBenchmark(true);
  }

  @Benchmark
  @Fork(
      value = FORKS,
      jvmArgsAppend = {
        "-Ddd.trace.enabled=false",
        "-Ddd.trace.startup.logs=false",
        "-Ddd.jmxfetch.enabled=false",
        "-Ddd.benchmark.instrumentation=callSite"
      })
  public void callSite() throws Exception {
    runBenchmark(true);
  }

  private void runBenchmark(final boolean transform) throws Exception {
    if (transform) {
      Agent.start(instrumentation, Agent.class.getProtectionDomain().getCodeSource().getLocation());
    }
    final Class<?> server = Class.forName("foo.bar.DummyApplication", true, classLoader);
    try (ConfigurableApplicationContext context = SpringApplication.run(server)) {
      final RestTemplate template = new RestTemplate();
      final String url = "http://localhost:8080/benchmark?param=Hello!";
      final String response = template.getForObject(url, String.class);
      if (response == null) {
        throw new RuntimeException("Empty response received");
      }
      final String expected = !transform ? "Hello!" : "Hello! [Transformed]";
      if (!expected.equals(response)) {
        throw new RuntimeException(
            String.format("Wrong response, expected '%s' but received '%s'", expected, response));
      }
    }
  }
}
