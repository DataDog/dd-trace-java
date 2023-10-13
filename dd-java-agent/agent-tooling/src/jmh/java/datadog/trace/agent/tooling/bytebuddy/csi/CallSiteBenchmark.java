package datadog.trace.agent.tooling.bytebuddy.csi;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.agent.tooling.AgentInstaller;
import java.lang.instrument.Instrumentation;
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
@Fork(value = 10)
public class CallSiteBenchmark {

  private Instrumentation instrumentation;

  @Setup(Level.Trial)
  public void setUp() {
    instrumentation = ByteBuddyAgent.install();
  }

  @Benchmark
  public void none() throws Exception {
    runBenchmark(Type.NONE);
  }

  @Benchmark
  public void callee() throws Exception {
    runBenchmark(Type.CALLEE);
  }

  @Benchmark
  public void callSite() throws Exception {
    runBenchmark(Type.CALL_SITE);
  }

  private void runBenchmark(final Type type) throws Exception {
    type.apply(instrumentation);
    final Class<?> server = Class.forName("foo.bar.DummyApplication");
    try (ConfigurableApplicationContext context = SpringApplication.run(server)) {
      final RestTemplate template = new RestTemplate();
      final String url = "http://localhost:8080/benchmark?param=Hello!";
      final String response = template.getForObject(url, String.class);
      type.validate(response);
    }
  }

  enum Type {
    NONE(null),
    CALL_SITE("callSite"),
    CALLEE("callee");

    private final String instrumentation;

    Type(final String instrumentation) {
      this.instrumentation = instrumentation;
    }

    public void apply(final Instrumentation instrumentation) {
      if (this.instrumentation != null) {
        System.setProperty("dd.benchmark.instrumentation", this.instrumentation);
        AgentInstaller.installBytebuddyAgent(instrumentation);
      }
    }

    public void validate(final String response) {
      if (response == null) {
        throw new RuntimeException("Empty response received");
      }
      String expected = instrumentation == null ? "Hello!" : "Hello! [Transformed]";
      if (!expected.equals(response)) {
        throw new RuntimeException(
            String.format("Wrong response, expected '%s' but received '%s'", expected, response));
      }
    }
  }
}
