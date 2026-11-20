package datadog.trace.common.sampling;

import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.common.sampling.RateSamplingRule.TraceSamplingRule;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Cost of matching a span against a list of trace sampling rules, as {@code RuleBasedTraceSampler}
 * walks them: every rule up to the first match. The rules mix the matcher kinds {@code
 * Matchers.compileGlob} produces (any, exact and wildcard globs), so the {@code Matcher} call sites
 * see the same mix of receiver types they do in production.
 *
 * <ul>
 *   <li>{@code first}: the span matches the first rule.
 *   <li>{@code last}: the span matches only the last rule, so every wildcard glob before it misses.
 *   <li>{@code none}: no rule matches, the common case when rules target a few endpoints.
 *   <li>{@code none-long}: as {@code none}, with a 500-character resource name, as SQL or URL
 *       resources can be.
 * </ul>
 *
 * <p>Run with {@code -prof gc}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Threads(4)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class SamplingRuleMatchBenchmark {
  @Param({"first", "last", "none", "none-long"})
  public String matching;

  private CoreTracer tracer;
  private DDSpan span;
  private TraceSamplingRule[] rules;

  @Setup
  public void setup() {
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    rules =
        new TraceSamplingRule[] {
          rule("checkout-*", "servlet.request", "GET /api/*/orders/*"),
          rule("*-prod", "*.query", null),
          rule("cart", "*", "POST /cart/??/*"),
          rule("web-*", "servlet.*", "* /health*"),
          rule(null, "grpc.server", "*.Checkout/*"),
        };

    final String service;
    final String operation;
    String resource;
    switch (matching) {
      case "first":
        service = "checkout-service";
        operation = "servlet.request";
        resource = "GET /api/v2/orders/12345";
        break;
      case "last":
        service = "payments";
        operation = "grpc.server";
        resource = "shop.Checkout/Place";
        break;
      case "none":
      case "none-long":
        service = "web-frontend";
        operation = "servlet.request";
        resource = "GET /api/v2/carts/12345";
        break;
      default:
        throw new IllegalArgumentException(matching);
    }
    if (matching.equals("none-long")) {
      final StringBuilder s = new StringBuilder(500 + resource.length());
      while (s.length() < 500) {
        s.append("SELECT x FROM t WHERE a = ? AND ");
      }
      resource = s.append(resource).toString();
    }

    span = (DDSpan) tracer.startSpan("benchmark", operation);
    span.setServiceName(service);
    span.setResourceName(resource);

    final int expected =
        matching.equals("first") ? 0 : matching.equals("last") ? rules.length - 1 : -1;
    if (firstMatch() != expected) {
      throw new IllegalStateException(matching + " matched rule " + firstMatch());
    }
  }

  @TearDown
  public void tearDown() {
    span.finish();
    tracer.close();
  }

  private static TraceSamplingRule rule(
      final String service, final String operation, final String resource) {
    return new TraceSamplingRule(
        service,
        operation,
        resource,
        emptyMap(),
        new DeterministicSampler.TraceSampler(1.0),
        SamplingMechanism.LOCAL_USER_RULE);
  }

  /** The index of the first rule the span matches, or -1. */
  @Benchmark
  public int firstMatch() {
    final TraceSamplingRule[] rules = this.rules;
    for (int i = 0; i < rules.length; i++) {
      if (rules[i].matches(span)) {
        return i;
      }
    }
    return -1;
  }
}
