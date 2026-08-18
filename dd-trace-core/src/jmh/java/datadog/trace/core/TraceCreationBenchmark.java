package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
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
 * Front-half allocation/throughput for creating a whole <em>trace</em> (a local-root span plus its
 * children), as opposed to the single-span {@link SpanCreationBenchmark}. One op = one complete
 * trace: root started, activated, children created-and-finished under it, then root finished.
 *
 * <p>Purpose — a reference others can key off when implementing core optimizations:
 *
 * <ul>
 *   <li><b>Win visibility.</b> Optimizations that touch per-span cost (trace-level tag sharing,
 *       context propagation, pending-trace bookkeeping) accrue <em>per span</em>, so a trace of
 *       {@code 1+childCount} spans surfaces the per-trace payoff a single-span bench cannot.
 *   <li><b>Regression guard.</b> A documented baseline (below) lets a later change to core show up
 *       as a delta — "did this addition move overhead?" — rather than passing silently.
 * </ul>
 *
 * <p>Isolation: children are created under an active root scope, so this exercises the real
 * parent-context propagation and trace-tag inheritance a single-span bench cannot reach. A no-op
 * {@link DropWriter} drops finished traces (handing them to a {@link Blackhole} so the JIT can't
 * dead-code the finish()-triggered write) so only application-thread (front-half) allocation lands
 * in the {@code -prof gc} number, with no serialization or agent I/O.
 *
 * <p>The tracer is production-shaped via {@code @Fork} jvmArgs (service/env/version + global {@code
 * dd.tags}) so {@code mergedTracerTags} is a realistically-sized shared bundle — the same config as
 * {@link SpanCreationBenchmark}, keeping numbers comparable across the two.
 *
 * <p>Read {@code gc.alloc.rate.norm} (B/op, deterministic) as the primary signal; throughput is
 * directional-only (laptop thermals + per-fork inlining bimodality).
 *
 * <p><b>Historical results</b> (populate from a committed run on the branch this file lives on;
 * matches the {@link SpanCreationBenchmark} header convention):
 *
 * <pre>
 *   date        commit           arm                        alloc B/op    thrpt ops/us
 *   ----        ------           ---                        ----------    ------------
 *   2026-08-18  sizing-hint-v2   webRequestTrace            4424.1        1.21
 *   2026-08-18  sizing-hint-v2   fanoutTrace   (child=1)    2632.0        2.42
 *   2026-08-18  sizing-hint-v2   fanoutTrace   (child=5)    6867.6        0.91
 *   2026-08-18  sizing-hint-v2   fanoutTrace   (child=10)  11167.7        0.50
 *
 *   @Fork(3) @Threads(8), dense on. webRequestTrace is flat across childCount
 *   (param ignored) — read any row.
 *
 *   Sizing A/B vs base #12047 (no SizingHint): fanoutTrace alloc win grows with
 *   childCount — ~0 @1, -6.3% (-756 B/op) @10 — from child-lane resize-avoidance
 *   on repeated heavy children; webRequestTrace (one-off mixed ops) shows ~0.
 *   Throughput held flat ON=OFF. Mean B/op under-sells this feature; the value is
 *   evolvability + worst-case (tail under fanout x tight heap), not the mean.
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(
    value = 3,
    jvmArgsAppend = {
      "-DTEST_LOG_LEVEL=warn",
      // Production-shaped tracer config so mergedTracerTags is a realistically-sized shared bundle
      // (env + 6 global DD_TAGS + runtime-id/language) — the trace-level tags every span merges.
      // Same config as SpanCreationBenchmark so the two benchmarks' numbers stay comparable.
      "-Ddd.service=petclinic",
      "-Ddd.env=staging",
      "-Ddd.version=1.2.3",
      "-Ddd.tags=team:apm,dc:us1,cluster:prod-1,owner:tracing,tier:backend,region:us-east-1"
    })
public class TraceCreationBenchmark {
  private static final String INSTRUMENTATION_NAME = "bench";
  private static final String SERVER_OPERATION_NAME = "servlet.request";
  private static final String JDBC_OPERATION_NAME = "database.query";
  private static final String HTTP_CLIENT_OPERATION_NAME = "http.request";
  private static final String INTERNAL_OPERATION_NAME = "internal.work";

  // Web-server (local root) tag shape — mirrors SpanCreationBenchmark.webServerSpan.
  private static final String COMPONENT_VALUE = "tomcat-server";
  private static final String HTTP_METHOD_VALUE = "GET";
  private static final String HTTP_ROUTE_VALUE = "/owners/{ownerId}";
  private static final String HTTP_URL_VALUE = "http://localhost:8080/owners/42";
  private static final int HTTP_STATUS_VALUE = 100; // in Integer cache; boxing does not allocate
  private static final int PEER_PORT_VALUE = 80;

  // JDBC-client child tag shape — mirrors SpanCreationBenchmark.jdbcClientSpan.
  private static final String DB_COMPONENT_VALUE = "java-jdbc-statement";
  private static final String DB_TYPE_VALUE = "postgresql";
  private static final String DB_INSTANCE_VALUE = "petclinic";
  private static final String DB_USER_VALUE = "app";
  private static final String DB_OPERATION_VALUE = "SELECT";
  private static final String DB_STATEMENT_VALUE = "SELECT * FROM owners WHERE id = ?";
  private static final String DB_PEER_HOSTNAME_VALUE = "db.internal";
  private static final int DB_PEER_PORT_VALUE = 90; // in Integer cache; boxing does not allocate

  // HTTP-client child tag shape.
  private static final String HTTP_CLIENT_COMPONENT_VALUE = "apache-httpclient";
  private static final String HTTP_CLIENT_URL_VALUE = "http://billing.internal/charge";
  private static final String HTTP_CLIENT_PEER_HOSTNAME_VALUE = "billing.internal";
  private static final int HTTP_CLIENT_PEER_PORT_VALUE = 90; // in Integer cache; no alloc

  // Internal child tag shape (a light span, few tags).
  private static final String INTERNAL_COMPONENT_VALUE = "spring-scheduler";

  /** Fan-out width for {@link #fanoutTrace()} — root + this many jdbc-shaped children. */
  @Param({"1", "5", "10"})
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

  /**
   * Fixed, realistic web request: a server local root with a JDBC-client, an HTTP-client, and an
   * internal child. One headline number that mirrors the shape of a real request. Not affected by
   * {@link #childCount} (JMH still runs it once per param value — read any single row).
   */
  @Benchmark
  public void webRequestTrace() {
    AgentSpan root = tracer.buildSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME).start();
    root.setTag(Tags.COMPONENT, COMPONENT_VALUE);
    root.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    root.setTag(Tags.HTTP_METHOD, HTTP_METHOD_VALUE);
    root.setTag(Tags.HTTP_ROUTE, HTTP_ROUTE_VALUE);
    root.setTag(Tags.HTTP_URL, HTTP_URL_VALUE);
    root.setTag(Tags.HTTP_STATUS, HTTP_STATUS_VALUE);
    root.setTag(Tags.PEER_PORT, PEER_PORT_VALUE);

    AgentScope scope = tracer.activateSpan(root);
    try {
      jdbcChild();
      httpClientChild();
      internalChild();
    } finally {
      scope.close();
    }
    root.finish();
  }

  /**
   * Fan-out trace: a server local root with {@link #childCount} JDBC-shaped children. The scaling
   * axis — per-trace cost as span count grows exposes how per-span optimizations compound.
   */
  @Benchmark
  public void fanoutTrace() {
    AgentSpan root = tracer.buildSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME).start();
    root.setTag(Tags.COMPONENT, COMPONENT_VALUE);
    root.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    root.setTag(Tags.HTTP_METHOD, HTTP_METHOD_VALUE);
    root.setTag(Tags.HTTP_ROUTE, HTTP_ROUTE_VALUE);
    root.setTag(Tags.HTTP_URL, HTTP_URL_VALUE);
    root.setTag(Tags.HTTP_STATUS, HTTP_STATUS_VALUE);
    root.setTag(Tags.PEER_PORT, PEER_PORT_VALUE);

    AgentScope scope = tracer.activateSpan(root);
    try {
      for (int i = 0; i < childCount; i++) {
        jdbcChild();
      }
    } finally {
      scope.close();
    }
    root.finish();
  }

  /** A JDBC-client child of the currently-active span. */
  private void jdbcChild() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, JDBC_OPERATION_NAME).start();
    span.setTag(Tags.COMPONENT, DB_COMPONENT_VALUE);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(Tags.DB_TYPE, DB_TYPE_VALUE);
    span.setTag(Tags.DB_INSTANCE, DB_INSTANCE_VALUE);
    span.setTag(Tags.DB_USER, DB_USER_VALUE);
    span.setTag(Tags.DB_OPERATION, DB_OPERATION_VALUE);
    span.setTag(Tags.DB_STATEMENT, DB_STATEMENT_VALUE);
    span.setTag(Tags.PEER_HOSTNAME, DB_PEER_HOSTNAME_VALUE);
    span.setTag(Tags.PEER_PORT, DB_PEER_PORT_VALUE);
    span.finish();
  }

  /** An HTTP-client child of the currently-active span. */
  private void httpClientChild() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, HTTP_CLIENT_OPERATION_NAME).start();
    span.setTag(Tags.COMPONENT, HTTP_CLIENT_COMPONENT_VALUE);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(Tags.HTTP_METHOD, HTTP_METHOD_VALUE);
    span.setTag(Tags.HTTP_URL, HTTP_CLIENT_URL_VALUE);
    span.setTag(Tags.HTTP_STATUS, HTTP_STATUS_VALUE);
    span.setTag(Tags.PEER_HOSTNAME, HTTP_CLIENT_PEER_HOSTNAME_VALUE);
    span.setTag(Tags.PEER_PORT, HTTP_CLIENT_PEER_PORT_VALUE);
    span.finish();
  }

  /** A light internal child of the currently-active span. */
  private void internalChild() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, INTERNAL_OPERATION_NAME).start();
    span.setTag(Tags.COMPONENT, INTERNAL_COMPONENT_VALUE);
    span.finish();
  }
}
