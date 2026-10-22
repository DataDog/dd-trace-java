package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
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
 * Span and trace scenarios built the way today's decorators build them: a {@link SpanPrototype}
 * applied in {@code afterStart}, then the request or connection tags by name. Each op finishes what
 * it starts, so allocation per op covers the whole span or trace.
 *
 * <ul>
 *   <li>{@code bareStartSpan} / {@code bareBuildSpan}: start (or build and start) a span with no
 *       tags, and finish it;
 *   <li>{@code webServerSpan}: a single-span trace following {@code HttpServerDecorator} ({@code
 *       afterStart}, {@code onRequest}, the route, the status);
 *   <li>{@code webServerSpanViaBuilder}: the same tags passed through the span builder;
 *   <li>{@code jdbcClientSpan}: a single-span trace following {@code JDBCDecorator} ({@code
 *       afterStart}, {@code onConnection}, {@code onPreparedStatement});
 *   <li>{@code webServerTrace}: a server root with {@code children} jdbc children, each activated
 *       and finished the way the advice does.
 * </ul>
 *
 * <p>Single-span and multi-span results can move in opposite directions: a change can make a
 * trace's root cheaper while charging every child, so read the {@code webServerTrace} rows too.
 * Values such as the client port and the status fall outside {@code Integer}'s box cache on
 * purpose.
 *
 * <p>Passing {@code -jvmArgsAppend} on the command line replaces the {@code @Fork} arguments below,
 * including {@code -DTEST_LOG_LEVEL=warn}; keep it, or debug logging dominates the allocation.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class SpanScenarioBenchmark {
  static final String SERVER_INSTRUMENTATION = "tomcat-server";
  static final CharSequence SERVER_OPERATION = UTF8BytesString.create("servlet.request");
  static final CharSequence SERVER_COMPONENT = UTF8BytesString.create("tomcat-server");
  static final CharSequence WEB = UTF8BytesString.create("web");
  static final String USER_AGENT =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0";
  static final String METHOD = "GET";
  static final String URL = "http://localhost:8080/owners/42";
  static final String HOSTNAME = "localhost";
  static final String CLIENT_IP = "127.0.0.1";
  static final int CLIENT_PORT = 54321; // ephemeral, outside the box cache
  static final String ROUTE = "/owners/{ownerId}";
  static final String SERVER_RESOURCE_NAME = "GET /owners/{ownerId}";
  static final CharSequence SERVER_RESOURCE = UTF8BytesString.create(SERVER_RESOURCE_NAME);
  static final int STATUS = 200; // outside the box cache

  static final String JDBC_INSTRUMENTATION = "java-jdbc-prepared_statement";
  static final CharSequence JDBC_OPERATION = UTF8BytesString.create("database.query");
  static final CharSequence JDBC_RENAMED_OPERATION = UTF8BytesString.create("postgresql.query");
  static final CharSequence JAVA_JDBC = UTF8BytesString.create("java-jdbc");
  static final CharSequence JDBC_PREPARED_STATEMENT =
      UTF8BytesString.create("java-jdbc-prepared_statement");
  static final CharSequence SQL = UTF8BytesString.create("sql");
  static final CharSequence STATEMENT = UTF8BytesString.create("SELECT * FROM owners WHERE id = ?");

  static final String BARE_INSTRUMENTATION = "bench";
  static final CharSequence BARE_OPERATION = UTF8BytesString.create("bare.op");

  /** The trace size, taken only by {@code webServerTrace} so the single-span scenarios run once. */
  @State(Scope.Benchmark)
  public static class Shape {
    @Param({"1", "5", "20"})
    int children;
  }

  CoreTracer tracer;
  SpanPrototype serverProto;
  SpanPrototype jdbcProto;

  @Setup
  public void setup(Blackhole blackhole) {
    this.tracer = CoreTracer.builder().writer(new DropWriter(blackhole)).build();
    // the decorators' prototype chains: Base (type, component) -> Server / Client (kind, language)
    this.serverProto =
        SpanPrototype.builder()
            .initInstrumentationName(SERVER_INSTRUMENTATION)
            .initOperationName(SERVER_OPERATION)
            .initSpanType(WEB)
            .initComponentAndIntegration(SERVER_COMPONENT)
            .initKind(Tags.SPAN_KIND_SERVER)
            .initTag("language", "jvm")
            .build();
    this.jdbcProto =
        SpanPrototype.builder()
            .initInstrumentationName(JDBC_INSTRUMENTATION)
            .initOperationName(JDBC_OPERATION)
            .initSpanType(SQL)
            .initComponentAndIntegration(JAVA_JDBC)
            .initKind(Tags.SPAN_KIND_CLIENT)
            .build();
  }

  @TearDown
  public void tearDown() {
    this.tracer.close();
  }

  @Benchmark
  public void bareStartSpan() {
    tracer.startSpan(BARE_INSTRUMENTATION, BARE_OPERATION).finish();
  }

  @Benchmark
  public void bareBuildSpan() {
    tracer.buildSpan(BARE_INSTRUMENTATION, BARE_OPERATION).start().finish();
  }

  @Benchmark
  public void webServerSpan() {
    AgentSpan server = startServer();
    server.setHttpStatusCode(STATUS);
    server.finish();
  }

  @Benchmark
  public void webServerSpanViaBuilder() {
    AgentSpan server =
        tracer
            .buildSpan(SERVER_INSTRUMENTATION, SERVER_OPERATION)
            .withSpanType(WEB)
            .withTag(Tags.COMPONENT, SERVER_COMPONENT)
            .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER)
            .withTag("language", "jvm")
            .withTag(Tags.HTTP_USER_AGENT, USER_AGENT)
            .withTag(Tags.HTTP_METHOD, METHOD)
            .withTag(Tags.HTTP_URL, URL)
            .withTag(Tags.HTTP_HOSTNAME, HOSTNAME)
            .withTag(Tags.PEER_HOST_IPV4, CLIENT_IP)
            .withTag(Tags.PEER_PORT, CLIENT_PORT)
            .withTag(Tags.HTTP_ROUTE, ROUTE)
            .withResourceName(SERVER_RESOURCE_NAME)
            .start();
    server.setHttpStatusCode(STATUS);
    server.finish();
  }

  @Benchmark
  public void jdbcClientSpan() {
    jdbc().finish();
  }

  @Benchmark
  public void webServerTrace(Shape shape) {
    AgentSpan server = startServer();
    try (ContextScope serverScope = tracer.activateSpan(server)) {
      for (int i = 0; i < shape.children; ++i) {
        AgentSpan jdbc = jdbc();
        ContextScope jdbcScope = tracer.activateSpan(jdbc);
        jdbcScope.close();
        jdbc.finish();
      }
    }
    server.setHttpStatusCode(STATUS);
    server.finish();
  }

  /** HttpServerDecorator: startSpan, afterStart (prototype), onRequest, then the route. */
  AgentSpan startServer() {
    AgentSpan server = tracer.startSpan(SERVER_INSTRUMENTATION, SERVER_OPERATION);
    server.applyOverwriting(serverProto);
    server.setTag(Tags.HTTP_USER_AGENT, USER_AGENT);
    server.setTag(Tags.HTTP_METHOD, METHOD);
    server.setTag(Tags.HTTP_URL, URL);
    server.setTag(Tags.HTTP_HOSTNAME, HOSTNAME);
    server.setResourceName(SERVER_RESOURCE); // HttpResourceDecorator.withServerPath
    server.setTag(Tags.PEER_HOST_IPV4, CLIENT_IP);
    server.setTag(Tags.PEER_PORT, CLIENT_PORT); // setPeerPort
    server.setTag(Tags.HTTP_ROUTE, ROUTE); // the framework, once routing resolves
    return server;
  }

  /**
   * JDBCDecorator over a postgres prepared statement: startSpan, afterStart (prototype),
   * onConnection, onPreparedStatement.
   */
  AgentSpan jdbc() {
    AgentSpan jdbc = tracer.startSpan(JDBC_INSTRUMENTATION, JDBC_OPERATION);
    jdbc.applyOverwriting(jdbcProto);
    jdbc.setTag(Tags.DB_TYPE, "postgresql"); // processDatabaseType
    jdbc.setServiceName("postgresql", JAVA_JDBC); // postProcessServiceAndOperationName
    jdbc.setOperationName(JDBC_RENAMED_OPERATION);
    jdbc.setTag(Tags.DB_POOL_NAME, "HikariPool-1");
    jdbc.setTag(Tags.DB_USER, "app"); // DatabaseClientDecorator.onConnection
    jdbc.setTag(Tags.DB_INSTANCE, "petclinic");
    jdbc.setTag(Tags.PEER_HOSTNAME, "db.internal");
    jdbc.setResourceName(STATEMENT); // withQueryInfo
    jdbc.setTag(Tags.DB_OPERATION, "SELECT");
    jdbc.spanContext().setIntegrationName(JDBC_PREPARED_STATEMENT);
    jdbc.setTag(Tags.COMPONENT, JDBC_PREPARED_STATEMENT);
    return jdbc;
  }
}
