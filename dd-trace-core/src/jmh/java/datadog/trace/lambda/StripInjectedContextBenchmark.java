package datadog.trace.lambda;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.nio.charset.StandardCharsets;
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
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 5, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class StripInjectedContextBenchmark {

  @Param({"true", "false"})
  boolean containsDatadogCarrier;

  byte[] payload;

  @Setup(Level.Trial)
  public void setUp() {
    String detail =
        containsDatadogCarrier
            ? "{\"orderId\":42,\"customer\":\"acme\",\"_datadog\":{\"x-datadog-trace-id\":\"123\",\"x-datadog-parent-id\":\"456\"}}"
            : "{\"orderId\":42,\"customer\":\"acme\"}";
    String envelope = "{\"detail-type\":\"order.created\",\"detail\":" + detail + "}";
    payload = envelope.getBytes(StandardCharsets.UTF_8);
  }

  @Benchmark
  public void stripInternal(Blackhole blackhole) {
    blackhole.consume(StripInjectedContext.stripInternal(payload));
  }
}
