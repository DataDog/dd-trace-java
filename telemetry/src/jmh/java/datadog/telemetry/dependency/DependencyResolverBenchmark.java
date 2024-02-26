package datadog.telemetry.dependency;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.net.URI;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@Warmup(iterations = 1, time = 1000, timeUnit = MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
@OutputTimeUnit(MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class DependencyResolverBenchmark {

  private static final String PROJECT_DIR = System.getProperty("user.dir");

  private static final URI SIMPLE_JAR_URI =
      URI.create(
          "file://"
              + PROJECT_DIR
              + "/src/test/resources/datadog/telemetry/dependencies/bson-4.2.0.jar");

  private static final URI NESTED_SPRING_BOOT_JAR_URI =
      URI.create(
          "jar:file://"
              + PROJECT_DIR
              + "/src/test/resources/datadog/telemetry/dependencies/spring-boot-app.jar!/BOOT-INF/lib/opentracing-util-0.33.0.jar!/");

  @Benchmark
  public void resolveSimpleJar() {
    final List<Dependency> result = DependencyResolver.resolve(SIMPLE_JAR_URI);
    assert !result.isEmpty();
  }

  @Benchmark
  public void resolveNestedSpringBootJar() {
    final List<Dependency> result = DependencyResolver.resolve(NESTED_SPRING_BOOT_JAR_URI);
    assert !result.isEmpty();
  }
}
