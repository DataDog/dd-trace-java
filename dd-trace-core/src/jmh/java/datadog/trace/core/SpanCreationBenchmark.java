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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Grounding benchmark for the full span-creation lifecycle: create -> (set tags) -> finish.
 *
 * <p>This is the "micro-ish" reference the TagMap 2.0 / SpanPrototype work is measured against. It
 * pairs a tag-free baseline with two known-tag shapes — a web-server span (7 tags) and a JDBC/DB
 * client span (9 tags) — so the dense-store and SpanPrototype allocation wins are actually
 * exercised (a tag-free or custom-tag benchmark would be dense-neutral and show nothing), and so
 * the marginal per-tag cost is visible across two realistic tag counts. It also covers the builder
 * tag path ({@code withTag} before {@code start()}, the OTel-bridge shape) alongside the
 * set-after-start path, so the startSpan/buildSpan lineages can be tracked as they diverge across
 * releases.
 *
 * <p><b>Read the allocation columns, not just throughput.</b> Run with {@code -prof gc}: {@code
 * gc.alloc.rate.norm} (B/op) is deterministic run-to-run and is the primary signal; throughput is
 * thermal/contention-fragile on a laptop and should be treated as directional. Multi-fork
 * ({@code @Fork(3)}) guards against per-fork inlining bimodality.
 *
 * <p><b>Deliberately drift-stable so it can be copied onto past release tags and back-checked.</b>
 * It touches only API that is byte-identical from v1.53.0 to master: {@link
 * CoreTracer#buildSpan(String, CharSequence)} / {@link CoreTracer#startSpan(String, CharSequence)},
 * {@code AgentSpan.setTag(String, ...)} / {@code finish()}, the {@link Tags} constants, and the
 * five-method {@code Writer} interface (implemented as a no-op {@link DropWriter}). If you add to
 * it, keep it inside that stable surface or grafting it onto old tags for the historical curve will
 * stop compiling. (Source rebuilds only reach ~v1.53 — older tags hit dead build-time dependencies;
 * deeper history is a published-jar job.)
 *
 * <p>Spans are finished against {@link DropWriter} so the create/tag/finish allocation is isolated
 * from serialization and agent I/O — those live on a different lever and would otherwise leak into
 * the {@code -prof gc} number via the writer's background threads.
 *
 * <p>Multi-threaded on purpose ({@code @Threads(8)}); some tracer optimizations only show under
 * contention. Use {@code -t 1} for a single-threaded run.
 *
 * <p><b>Historical allocation</b> (B/op, {@code gc.alloc.rate.norm}), this benchmark grafted onto
 * each release tag and run {@code @Threads(8) -f3 -wi5 -i5 -prof gc} (measured 2026-07). The ~1.59
 * drop is the TagMap 1.0 shared-Entry default flip; the ~1.61 drop is the interceptor/links
 * cluster. Net 1.53 &rarr; 1.64 is -20% to -31% per arm.
 *
 * <pre>
 * ver    bareStart bareBuild webServer viaBuilder jdbcClient
 * 1.53      1330.7    1331.1    2058.7     2434.7     1821.3
 * 1.54      1423.8    1423.8    2131.4     2491.1     2037.8
 * 1.55      1308.8    1422.0    2098.1     2477.2     2029.4
 * 1.56      1322.2    1393.0    2131.5     2464.2     2016.6
 * 1.57      1321.6    1363.3    2063.3     2410.6     2073.8
 * 1.58      1312.6    1310.5    2018.0     2442.1     2065.3
 * 1.59      1174.9    1166.1    1869.0     1987.2     1866.0
 * 1.60      1180.6    1192.1    1858.9     1963.3     1818.0
 * 1.61       959.2     951.7    1639.2     1774.1     1619.4
 * 1.62       948.0     948.7    1674.9     1787.1     1614.1
 * 1.63       927.0     926.7    1626.8     1737.7     1429.8
 * 1.64       923.3     958.4    1636.6     1751.1     1421.5
 * Δ%         -30.6     -28.0     -20.5      -28.1      -22.0
 * </pre>
 *
 * <p>Throughput (ops/us) from the same runs — <b>noisier, treat as directional only</b> (laptop
 * thermals + per-fork inlining bimodality; no Δ% given because there is no reliable trend):
 *
 * <pre>
 * ver    bareStart bareBuild webServer viaBuilder jdbcClient
 * 1.53        5.69      5.49      4.00       3.99       5.33
 * 1.54        4.26      4.25      3.47       3.55       3.15
 * 1.55        3.87      4.13      3.37       3.52       3.14
 * 1.56        3.92      4.03      3.79       3.33       3.15
 * 1.57        4.21      4.11      3.31       3.49       3.37
 * 1.58        4.16      4.19      3.34       3.52       3.27
 * 1.59        4.20      4.66      3.43       3.52       3.45
 * 1.60        5.48      5.66      3.42       3.54       3.77
 * 1.61        4.17      4.31      3.46       3.58       3.46
 * 1.62        5.53      5.72      4.20       4.37       4.49
 * 1.63        6.03      5.97      4.66       5.13       4.49
 * 1.64        5.37      5.26      4.62       5.29       5.06
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3)
public class SpanCreationBenchmark {
  private static final String INSTRUMENTATION_NAME = "bench";
  private static final String OPERATION_NAME = "servlet.request";

  // int tag values are deliberately kept inside Integer's built-in cache (-128..127) so valueOf
  // returns a shared box and boxing does not allocate — the bench then measures tag storage / path
  // cost, not incidental boxing (which differs between setTag(int) and the builder's
  // withTag(Number)).

  // Web-server-shaped known tags — the profile the dense store / SpanPrototype target.
  private static final String COMPONENT_VALUE = "tomcat-server";
  private static final String HTTP_METHOD_VALUE = "GET";
  private static final String HTTP_ROUTE_VALUE = "/owners/{ownerId}";
  private static final String HTTP_URL_VALUE = "http://localhost:8080/owners/42";
  private static final int HTTP_STATUS_VALUE = 100; // in-cache; value itself is immaterial here
  private static final int PEER_PORT_VALUE = 80;

  // JDBC/DB-client-shaped known tags — a higher-tag-count shape (9 vs the web shape's 7), matching
  // what DatabaseClientDecorator + JDBCDecorator set on a statement span.
  private static final String DB_COMPONENT_VALUE = "java-jdbc-statement";
  private static final String DB_TYPE_VALUE = "postgresql";
  private static final String DB_INSTANCE_VALUE = "petclinic";
  private static final String DB_USER_VALUE = "app";
  private static final String DB_OPERATION_VALUE = "SELECT";
  private static final String DB_STATEMENT_VALUE = "SELECT * FROM owners WHERE id = ?";
  private static final String DB_PEER_HOSTNAME_VALUE = "db.internal";
  private static final int DB_PEER_PORT_VALUE = 90; // in-cache; value itself is immaterial here

  CoreTracer tracer;

  @Setup
  public void setup() {
    // DropWriter keeps finish() from pulling in serialization / agent I/O, so -prof gc reflects
    // span creation + tagging + PendingTrace completion only.
    this.tracer = CoreTracer.builder().writer(new DropWriter()).build();
  }

  @TearDown
  public void tearDown() {
    this.tracer.close();
  }

  /** Baseline: create + finish a bare span via startSpan, no tags. */
  @Benchmark
  public void bareStartSpan() {
    AgentSpan span = tracer.startSpan(INSTRUMENTATION_NAME, OPERATION_NAME);
    span.finish();
  }

  /** Baseline: create + finish a bare span via the builder path, no tags. */
  @Benchmark
  public void bareBuildSpan() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, OPERATION_NAME).start();
    span.finish();
  }

  /** Web-server-shaped span: create -> set the typical known tags -> finish. */
  @Benchmark
  public void webServerSpan() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, OPERATION_NAME).start();
    span.setTag(Tags.COMPONENT, COMPONENT_VALUE);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    span.setTag(Tags.HTTP_METHOD, HTTP_METHOD_VALUE);
    span.setTag(Tags.HTTP_ROUTE, HTTP_ROUTE_VALUE);
    span.setTag(Tags.HTTP_URL, HTTP_URL_VALUE);
    span.setTag(Tags.HTTP_STATUS, HTTP_STATUS_VALUE);
    span.setTag(Tags.PEER_PORT, PEER_PORT_VALUE);
    span.finish();
  }

  /**
   * Web-server-shaped span via the <b>builder tag path</b>: tags accumulated on the builder with
   * {@code withTag} and applied at {@code start()}, rather than set on the span afterward. This is
   * the shape the OTel bridge takes (OTel {@code SpanBuilder.setAttribute} → dd builder), still
   * live today for manual OTel and OTel-bridge auto-instrumentation. Compare against {@link
   * #webServerSpan} (same tags, set after start) to track how the startSpan/buildSpan paths diverge
   * across releases.
   */
  @Benchmark
  public void webServerSpanViaBuilder() {
    AgentSpan span =
        tracer
            .buildSpan(INSTRUMENTATION_NAME, OPERATION_NAME)
            .withTag(Tags.COMPONENT, COMPONENT_VALUE)
            .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER)
            .withTag(Tags.HTTP_METHOD, HTTP_METHOD_VALUE)
            .withTag(Tags.HTTP_ROUTE, HTTP_ROUTE_VALUE)
            .withTag(Tags.HTTP_URL, HTTP_URL_VALUE)
            .withTag(Tags.HTTP_STATUS, HTTP_STATUS_VALUE)
            .withTag(Tags.PEER_PORT, PEER_PORT_VALUE)
            .start();
    span.finish();
  }

  /** JDBC/DB-client-shaped span: create -> set the typical DB known tags (9) -> finish. */
  @Benchmark
  public void jdbcClientSpan() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, OPERATION_NAME).start();
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
}
