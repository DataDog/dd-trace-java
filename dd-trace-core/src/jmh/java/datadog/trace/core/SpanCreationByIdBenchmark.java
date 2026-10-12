package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
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
 * {@link SpanCreationBenchmark}'s tagged span shapes, setting each tag by its {@link KnownTags} id
 * instead of its name: same tags, values, threads and forks, so each arm here compares directly
 * with the same-named arm there. {@code peer.port} uses its directional id ({@code
 * PEER_PORT_INBOUND_ID} on the server span, {@code PEER_PORT_OUTBOUND_ID} on the client span),
 * which a by-name write resolves through the span's kind. The bare spans set no tags, and the
 * builder path has no id-keyed {@code withTag}, so those arms are not repeated.
 *
 * <p>Read {@code gc.alloc.rate.norm} (B/op) first, as for {@link SpanCreationBenchmark}; throughput
 * is directional. Run with {@code -prof gc}, and append {@code -DTEST_LOG_LEVEL=warn} to any {@code
 * -jvmArgsAppend}, which replaces the {@code @Fork} arguments below.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class SpanCreationByIdBenchmark {
  private static final String INSTRUMENTATION_NAME = "bench";
  private static final String SERVER_OPERATION_NAME = "servlet.request";
  private static final String JDBC_OPERATION_NAME = "database.query";

  // Same values as SpanCreationBenchmark; int values stay inside Integer's cache.
  private static final String COMPONENT_VALUE = "tomcat-server";
  private static final String HTTP_METHOD_VALUE = "GET";
  private static final String HTTP_ROUTE_VALUE = "/owners/{ownerId}";
  private static final String HTTP_URL_VALUE = "http://localhost:8080/owners/42";
  private static final int HTTP_STATUS_VALUE = 100;
  private static final int PEER_PORT_VALUE = 80;

  private static final String DB_COMPONENT_VALUE = "java-jdbc-statement";
  private static final String DB_TYPE_VALUE = "postgresql";
  private static final String DB_INSTANCE_VALUE = "petclinic";
  private static final String DB_USER_VALUE = "app";
  private static final String DB_OPERATION_VALUE = "SELECT";
  private static final String DB_STATEMENT_VALUE = "SELECT * FROM owners WHERE id = ?";
  private static final String DB_PEER_HOSTNAME_VALUE = "db.internal";
  private static final int DB_PEER_PORT_VALUE = 90;

  CoreTracer tracer;
  SpanPrototype webProto;
  SpanPrototype jdbcProto;

  @Setup
  public void setup(Blackhole blackhole) {
    this.tracer = CoreTracer.builder().writer(new DropWriter(blackhole)).build();
    this.webProto =
        SpanPrototype.builder()
            .initInstrumentationName(INSTRUMENTATION_NAME)
            .initOperationName(SERVER_OPERATION_NAME)
            .initComponentOnly(COMPONENT_VALUE)
            .initKind(Tags.SPAN_KIND_SERVER)
            .build();
    this.jdbcProto =
        SpanPrototype.builder()
            .initInstrumentationName(INSTRUMENTATION_NAME)
            .initOperationName(JDBC_OPERATION_NAME)
            .initComponentOnly(DB_COMPONENT_VALUE)
            .initKind(Tags.SPAN_KIND_CLIENT)
            .initTag(Tags.DB_TYPE, DB_TYPE_VALUE)
            .build();
  }

  @TearDown
  public void tearDown() {
    this.tracer.close();
  }

  /** {@link SpanCreationBenchmark#webServerSpan}, by id. */
  @Benchmark
  public void webServerSpan() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME).start();
    span.setTag(KnownTags.COMPONENT_ID, COMPONENT_VALUE);
    span.setTag(KnownTags.SPAN_KIND_ID, Tags.SPAN_KIND_SERVER);
    span.setTag(KnownTags.HTTP_METHOD_ID, HTTP_METHOD_VALUE);
    span.setTag(KnownTags.HTTP_ROUTE_ID, HTTP_ROUTE_VALUE);
    span.setTag(KnownTags.HTTP_URL_ID, HTTP_URL_VALUE);
    span.setTag(KnownTags.HTTP_STATUS_CODE_ID, HTTP_STATUS_VALUE);
    span.setTag(KnownTags.PEER_PORT_INBOUND_ID, PEER_PORT_VALUE);
    span.finish();
  }

  /** {@link SpanCreationBenchmark#jdbcClientSpan}, by id. */
  @Benchmark
  public void jdbcClientSpan() {
    AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, JDBC_OPERATION_NAME).start();
    span.setTag(KnownTags.COMPONENT_ID, DB_COMPONENT_VALUE);
    span.setTag(KnownTags.SPAN_KIND_ID, Tags.SPAN_KIND_CLIENT);
    span.setTag(KnownTags.DB_TYPE_ID, DB_TYPE_VALUE);
    span.setTag(KnownTags.DB_INSTANCE_ID, DB_INSTANCE_VALUE);
    span.setTag(KnownTags.DB_USER_ID, DB_USER_VALUE);
    span.setTag(KnownTags.DB_OPERATION_ID, DB_OPERATION_VALUE);
    span.setTag(KnownTags.DB_STATEMENT_ID, DB_STATEMENT_VALUE);
    span.setTag(KnownTags.PEER_HOSTNAME_ID, DB_PEER_HOSTNAME_VALUE);
    span.setTag(KnownTags.PEER_PORT_OUTBOUND_ID, DB_PEER_PORT_VALUE);
    span.finish();
  }

  /** {@link SpanCreationBenchmark#webServerSpanViaPrototype}, by id. */
  @Benchmark
  public void webServerSpanViaPrototype() {
    AgentSpan span = tracer.buildSpan(webProto, null).start();
    span.setTag(KnownTags.HTTP_METHOD_ID, HTTP_METHOD_VALUE);
    span.setTag(KnownTags.HTTP_ROUTE_ID, HTTP_ROUTE_VALUE);
    span.setTag(KnownTags.HTTP_URL_ID, HTTP_URL_VALUE);
    span.setTag(KnownTags.HTTP_STATUS_CODE_ID, HTTP_STATUS_VALUE);
    span.setTag(KnownTags.PEER_PORT_INBOUND_ID, PEER_PORT_VALUE);
    span.finish();
  }

  /** {@link SpanCreationBenchmark#jdbcClientSpanViaPrototype}, by id. */
  @Benchmark
  public void jdbcClientSpanViaPrototype() {
    AgentSpan span = tracer.buildSpan(jdbcProto, null).start();
    span.setTag(KnownTags.DB_INSTANCE_ID, DB_INSTANCE_VALUE);
    span.setTag(KnownTags.DB_USER_ID, DB_USER_VALUE);
    span.setTag(KnownTags.DB_OPERATION_ID, DB_OPERATION_VALUE);
    span.setTag(KnownTags.DB_STATEMENT_ID, DB_STATEMENT_VALUE);
    span.setTag(KnownTags.PEER_HOSTNAME_ID, DB_PEER_HOSTNAME_VALUE);
    span.setTag(KnownTags.PEER_PORT_OUTBOUND_ID, DB_PEER_PORT_VALUE);
    span.finish();
  }

  /** {@link SpanCreationBenchmark#webServerSpanViaPrototypeStartSpan}, by id. */
  @Benchmark
  public void webServerSpanViaPrototypeStartSpan() {
    AgentSpan span = tracer.startSpan(webProto, null);
    span.setTag(KnownTags.HTTP_METHOD_ID, HTTP_METHOD_VALUE);
    span.setTag(KnownTags.HTTP_ROUTE_ID, HTTP_ROUTE_VALUE);
    span.setTag(KnownTags.HTTP_URL_ID, HTTP_URL_VALUE);
    span.setTag(KnownTags.HTTP_STATUS_CODE_ID, HTTP_STATUS_VALUE);
    span.setTag(KnownTags.PEER_PORT_INBOUND_ID, PEER_PORT_VALUE);
    span.finish();
  }

  /** {@link SpanCreationBenchmark#jdbcClientSpanViaPrototypeStartSpan}, by id. */
  @Benchmark
  public void jdbcClientSpanViaPrototypeStartSpan() {
    AgentSpan span = tracer.startSpan(jdbcProto, null);
    span.setTag(KnownTags.DB_INSTANCE_ID, DB_INSTANCE_VALUE);
    span.setTag(KnownTags.DB_USER_ID, DB_USER_VALUE);
    span.setTag(KnownTags.DB_OPERATION_ID, DB_OPERATION_VALUE);
    span.setTag(KnownTags.DB_STATEMENT_ID, DB_STATEMENT_VALUE);
    span.setTag(KnownTags.PEER_HOSTNAME_ID, DB_PEER_HOSTNAME_VALUE);
    span.setTag(KnownTags.PEER_PORT_OUTBOUND_ID, DB_PEER_PORT_VALUE);
    span.finish();
  }
}
