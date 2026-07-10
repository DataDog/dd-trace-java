package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
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
 * Front-half (application-thread) benchmark for <b>multi-span trace assembly</b>: a
 * web-server-shaped parent plus N children, each finished, whole trace dropped.
 *
 * <p>Where {@link SpanCreationBenchmark} measures a single span (front-A), this measures the cost
 * that <i>scales with trace size</i> (front-B): every span created copies the tracer's baseline /
 * trace-level tags into its own storage, so an N-span trace pays that copy N+1 times. That per-span
 * copy is exactly what the map-to-map copy optimization (TagMap 1.0) and the trace/span tag split
 * (level-split read-through) target — this bench is how their win shows up as it scales, which a
 * single-span bench cannot show.
 *
 * <p>Sweeping {@code childCount} turns the per-child marginal cost into the slope: the level-split
 * win should flatten it (children stop re-copying trace-level tags).
 *
 * <p>Same conventions as {@link SpanCreationBenchmark}: {@link DropWriter} isolates front-half
 * allocation; read alloc ({@code -prof gc}) as the anchor, throughput as directional; logging must
 * be forced to WARN or DEBUG-line allocation corrupts the numbers. Drift-stable v1.53→master
 * ({@code buildSpan}/{@code asChildOf}/{@code setTag}/{@code finish}, {@link Tags}, {@link
 * datadog.trace.common.writer.Writer}) so it can be grafted onto old tags for the historical curve.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3)
public class TraceAssemblyBenchmark {
  private static final String INSTRUMENTATION_NAME = "bench";
  private static final String ROOT_OPERATION = "servlet.request";
  private static final String CHILD_OPERATION = "servlet.handler";

  private static final String COMPONENT_VALUE = "tomcat-server";
  private static final String HTTP_METHOD_VALUE = "GET";
  private static final String HTTP_ROUTE_VALUE = "/owners/{ownerId}";
  private static final String HTTP_URL_VALUE = "http://localhost:8080/owners/42";
  private static final int HTTP_STATUS_VALUE = 200;

  /** Number of child spans under the root — the axis that turns per-child cost into a slope. */
  @Param({"1", "5", "20"})
  int childCount;

  CoreTracer tracer;

  @Setup
  public void setup() {
    this.tracer = CoreTracer.builder().writer(new DropWriter()).build();
  }

  @TearDown
  public void tearDown() {
    this.tracer.close();
  }

  /** Web-server root + {@code childCount} children, each finished; whole trace dropped. */
  @Benchmark
  public void webServerTrace() {
    AgentSpan root = tracer.buildSpan(INSTRUMENTATION_NAME, ROOT_OPERATION).start();
    root.setTag(Tags.COMPONENT, COMPONENT_VALUE);
    root.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    root.setTag(Tags.HTTP_METHOD, HTTP_METHOD_VALUE);
    root.setTag(Tags.HTTP_ROUTE, HTTP_ROUTE_VALUE);
    root.setTag(Tags.HTTP_URL, HTTP_URL_VALUE);
    root.setTag(Tags.HTTP_STATUS, HTTP_STATUS_VALUE);

    for (int i = 0; i < childCount; i++) {
      AgentSpan child =
          tracer.buildSpan(INSTRUMENTATION_NAME, CHILD_OPERATION).asChildOf(root).start();
      child.setTag(Tags.COMPONENT, COMPONENT_VALUE);
      child.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_INTERNAL);
      child.finish();
    }

    root.finish();
  }
}
