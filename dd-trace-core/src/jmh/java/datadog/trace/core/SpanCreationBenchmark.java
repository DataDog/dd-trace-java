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
import org.openjdk.jmh.infra.Blackhole;

/**
 * Cross-version portable span-creation benchmark: create -> (set tags) -> finish. Only the
 * drift-stable old-API arms (buildSpan/startSpan + setTag/withTag + Tags constants + the
 * five-method Writer) so it compiles byte-identically on v1.53..master and can be grafted onto any
 * release tag.
 *
 * <p>Used for the #12047 (dense tag store + graph-colored slots) vs v1.65.0 A/B. The dense store is
 * activated on the #12047 build by {@code -Ddd.trace.dense.tags.enabled=true} in the {@code @Fork}
 * args below; the same flag is an unknown/no-op property on v1.65.0, so the ONLY difference between
 * the two runs is the tracer version. Read {@code gc.alloc.rate.norm} (B/op, deterministic) as the
 * primary signal; throughput is directional-only (per-fork JIT bimodality at @Threads(8)).
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
      // Activates the dense known-tag store on #12047; unknown/no-op property on v1.65.0.
      "-Ddd.trace.dense.tags.enabled=true",
      // Production-shaped tracer config so mergedTracerTags is a realistically-sized shared bundle.
      "-Ddd.service=petclinic",
      "-Ddd.env=staging",
      "-Ddd.version=1.2.3",
      "-Ddd.tags=team:apm,dc:us1,cluster:prod-1,owner:tracing,tier:backend,region:us-east-1"
    })
public class SpanCreationBenchmark {
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

  /** Baseline: create + finish a bare span via startSpan, no tags. */
  @Benchmark
  public void bareStartSpan() {
    AgentSpan span = tracer.startSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME);
    span.finish();
  }

  /** Baseline: create + finish a bare span via the builder path, no tags. */
  @Benchmark
  public void bareBuildSpan() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME).start();
    span.finish();
  }

  /** Web-server-shaped span: create -> set the typical known tags (7) -> finish. */
  @Benchmark
  public void webServerSpan() {
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

  /**
   * Web-server-shaped span via the builder tag path (withTag before start, the OTel-bridge shape).
   */
  @Benchmark
  public void webServerSpanViaBuilder() {
    AgentSpan span =
        tracer
            .buildSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME)
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
}
