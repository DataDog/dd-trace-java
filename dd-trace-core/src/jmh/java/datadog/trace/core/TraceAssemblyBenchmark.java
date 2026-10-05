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
import org.openjdk.jmh.infra.Blackhole;

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
 *
 * <p><b>Historical allocation</b> (B/op, {@code gc.alloc.rate.norm}) per {@code childCount},
 * grafted onto each release tag, {@code @Threads(8) -f3 -wi5 -i5 -prof gc} (measured 2026-07). The
 * per-child slope is the level-split target: note the total falls far less than the single-span
 * arms (net -13% to -20% vs -20% to -31%), because most of an N-span trace's allocation is the
 * per-child trace-level-tag copy that TagMap 1.0 does not yet remove — the level-split read-through
 * is what should flatten it.
 *
 * <pre>
 * ver    trace[1] trace[5] trace[20]
 * 1.53     2792.0   5701.3   16674.7
 * 1.54     2874.7   5773.3   16728.0
 * 1.55     2816.7   5752.0   16733.4
 * 1.56     2826.7   5762.7   16728.0
 * 1.57     2746.7   5301.3   15058.7
 * 1.58     2640.0   5642.7   15072.0
 * 1.59     2504.0   5376.0   15370.7
 * 1.60     2519.2   5264.0   14986.7
 * 1.61     2240.0   4634.7   14258.7
 * 1.62     2256.2   4650.7   13688.7
 * 1.63     2238.6   4868.9   14992.0
 * 1.64     2241.3   4720.5   14592.0
 * Δ%        -19.7    -17.2     -12.5
 * </pre>
 *
 * <p>Throughput (ops/us) from the same runs — <b>noisier, treat as directional only</b> (laptop
 * thermals + per-fork inlining bimodality; no Δ% given because there is no reliable trend):
 *
 * <pre>
 * ver    trace[1] trace[5] trace[20]
 * 1.53      2.428    0.781     0.247
 * 1.54      2.318    0.840     0.260
 * 1.55      2.355    0.931     0.254
 * 1.56      2.515    0.904     0.278
 * 1.57      2.296    0.953     0.305
 * 1.58      2.481    1.049     0.301
 * 1.59      2.285    1.022     0.276
 * 1.60      2.297    0.980     0.303
 * 1.61      2.371    0.884     0.258
 * 1.62      2.341    1.141     0.296
 * 1.63      2.911    1.039     0.247
 * 1.64      2.422    0.944     0.253
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
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
  public void setup(Blackhole blackhole) {
    this.tracer = CoreTracer.builder().writer(new DropWriter(blackhole)).build();
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
