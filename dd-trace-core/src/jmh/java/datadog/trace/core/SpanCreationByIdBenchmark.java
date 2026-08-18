package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.api.KnownTags;
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
import org.openjdk.jmh.infra.Blackhole;

/**
 * Name-vs-id span-creation benchmark: the same web/jdbc span scenarios as {@link
 * SpanCreationBenchmark}, set two ways in the SAME JVM — {@code setTag(String, ...)} (the {@code
 * *ByName} arms, which resolve {@code keyOf} on every call) vs {@code setTag(long, ...)} with
 * pre-resolved {@link KnownTags} id constants (the {@code *ById} arms, which skip {@code keyOf}
 * and, for non-intercepted tags, the tag interceptor). This isolates the THROUGHPUT lever of the id
 * API: both arms produce identical span state and allocate identically (the dense store is the
 * same), so the delta is the {@code keyOf} name-resolution + megamorphic-dispatch tax the id path
 * removes.
 *
 * <p>Not every tag reaches the fast store path. Ids whose interceptor bit is set (span.kind,
 * http.method, http.url, db.statement) route back through the String path inside {@code
 * DDSpan.setTag(long, ...)}, so they behave exactly as the name arm — the {@code ById} win comes
 * from the non-intercepted majority (component, http.route, peer.port, db.type/instance/user/
 * operation, peer.hostname). This is the realistic shape of instrumentation migrated to ids: a
 * uniform id call site, fast where the tag allows it. {@code http.status_code} is left on the
 * String setter in BOTH arms — its int overload carries a span-field side effect
 * (setHttpStatusCode) the id fast path intentionally doesn't, so keeping it name-keyed holds the
 * two arms behaviorally equal.
 *
 * <p>Run with the dense store on ({@code -Ddd.trace.dense.tags.enabled=true}, in the {@code @Fork}
 * args). Read {@code gc.alloc.rate.norm} (B/op) to confirm the arms allocate the same; read
 * throughput for the id win (directional — per-fork JIT bimodality at @Threads(8)).
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
      "-Ddd.trace.dense.tags.enabled=true",
      "-Ddd.service=petclinic",
      "-Ddd.env=staging",
      "-Ddd.version=1.2.3",
      "-Ddd.tags=team:apm,dc:us1,cluster:prod-1,owner:tracing,tier:backend,region:us-east-1"
    })
public class SpanCreationByIdBenchmark {
  private static final String INSTRUMENTATION_NAME = "bench";
  private static final String SERVER_OPERATION_NAME = "servlet.request";
  private static final String JDBC_OPERATION_NAME = "database.query";

  private static final String COMPONENT_VALUE = "tomcat-server";
  private static final String HTTP_METHOD_VALUE = "GET";
  private static final String HTTP_ROUTE_VALUE = "/owners/{ownerId}";
  private static final String HTTP_URL_VALUE = "http://localhost:8080/owners/42";
  private static final int HTTP_STATUS_VALUE = 100; // in-cache; value itself is immaterial here
  private static final int PEER_PORT_VALUE = 80;

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
  public void setup(Blackhole blackhole) {
    this.tracer = CoreTracer.builder().writer(new DropWriter(blackhole)).build();
  }

  @TearDown
  public void tearDown() {
    this.tracer.close();
  }

  /** Web-server-shaped span, tags set by NAME (keyOf on every call). */
  @Benchmark
  public void webServerSpanByName() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME).start();
    span.setTag(Tags.COMPONENT, COMPONENT_VALUE);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    span.setTag(Tags.HTTP_METHOD, HTTP_METHOD_VALUE);
    span.setTag(Tags.HTTP_ROUTE, HTTP_ROUTE_VALUE);
    span.setTag(Tags.HTTP_URL, HTTP_URL_VALUE);
    span.setTag(Tags.HTTP_STATUS, HTTP_STATUS_VALUE);
    span.setTag(Tags.PEER_PORT, PEER_PORT_VALUE);
    span.finish();
  }

  /** Web-server-shaped span, tags set by ID (pre-resolved KnownTags constants, no keyOf). */
  @Benchmark
  public void webServerSpanById() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME).start();
    span.setTag(KnownTags.COMPONENT_ID, COMPONENT_VALUE);
    span.setTag(KnownTags.SPAN_KIND_ID, Tags.SPAN_KIND_SERVER);
    span.setTag(KnownTags.HTTP_METHOD_ID, HTTP_METHOD_VALUE);
    span.setTag(KnownTags.HTTP_ROUTE_ID, HTTP_ROUTE_VALUE);
    span.setTag(KnownTags.HTTP_URL_ID, HTTP_URL_VALUE);
    span.setTag(Tags.HTTP_STATUS, HTTP_STATUS_VALUE); // name-keyed in both arms (see class doc)
    span.setTag(KnownTags.PEER_PORT_ID, PEER_PORT_VALUE);
    span.finish();
  }

  /** JDBC/DB-client-shaped span, tags set by NAME. */
  @Benchmark
  public void jdbcClientSpanByName() {
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

  /** JDBC/DB-client-shaped span, tags set by ID (7 of 9 reach the fast store path). */
  @Benchmark
  public void jdbcClientSpanById() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, JDBC_OPERATION_NAME).start();
    span.setTag(KnownTags.COMPONENT_ID, DB_COMPONENT_VALUE);
    span.setTag(KnownTags.SPAN_KIND_ID, Tags.SPAN_KIND_CLIENT);
    span.setTag(KnownTags.DB_TYPE_ID, DB_TYPE_VALUE);
    span.setTag(KnownTags.DB_INSTANCE_ID, DB_INSTANCE_VALUE);
    span.setTag(KnownTags.DB_USER_ID, DB_USER_VALUE);
    span.setTag(KnownTags.DB_OPERATION_ID, DB_OPERATION_VALUE);
    span.setTag(KnownTags.DB_STATEMENT_ID, DB_STATEMENT_VALUE);
    span.setTag(KnownTags.PEER_HOSTNAME_ID, DB_PEER_HOSTNAME_VALUE);
    span.setTag(KnownTags.PEER_PORT_ID, DB_PEER_PORT_VALUE);
    span.finish();
  }
}
