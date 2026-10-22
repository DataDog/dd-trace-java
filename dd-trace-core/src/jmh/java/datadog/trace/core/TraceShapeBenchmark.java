package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.context.ContextScope;
import datadog.trace.api.KnownTags;
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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * One whole trace per op, shaped like a web request: a server root, five jdbc prepared-statement
 * children and one {@code @Trace} internal span, all finished. Divide by 7 for per-span figures.
 *
 * <p>For retrospective comparison across tracer versions, each version keeps its own copy of this
 * benchmark, so the arms reflect that version's code:
 *
 * <ul>
 *   <li>{@code legacy}: every tag set by name. It runs unchanged on every version, so it isolates
 *       the tracer's own costs.
 *   <li>{@code normal}: the calls this version's decorators make (copied from {@code
 *       HttpServerDecorator}, {@code JDBCDecorator} / {@code DatabaseClientDecorator} and {@code
 *       BaseDecorator} at this version), with request parsing replaced by fixed values. This is the
 *       version's real cost.
 *   <li>{@code newApi}: this version's newest span APIs, where they exist: here, construction from
 *       a {@link SpanPrototype} and tags set by id.
 * </ul>
 *
 * <p>Tag values are realistic on purpose: the client port, the database port and the status fall
 * outside {@code Integer}'s box cache, so primitive boxing shows up.
 *
 * <p>This copy: 1.68 snapshot (decorators apply a prototype in {@code afterStart}, #12037; peer
 * tags by id, #12715).
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class TraceShapeBenchmark {
  static final int JDBC_SPANS = 5;

  // server: HttpServerDecorator (tomcat)
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
  static final CharSequence SERVER_RESOURCE = UTF8BytesString.create("GET /owners/{ownerId}");
  static final int STATUS = 200; // outside the box cache

  // jdbc: JDBCDecorator over a postgres prepared statement
  static final String JDBC_INSTRUMENTATION = "java-jdbc-prepared_statement";
  static final CharSequence JDBC_OPERATION = UTF8BytesString.create("database.query");
  static final CharSequence JDBC_RENAMED_OPERATION = UTF8BytesString.create("postgresql.query");
  static final CharSequence JAVA_JDBC = UTF8BytesString.create("java-jdbc");
  static final CharSequence JDBC_PREPARED_STATEMENT =
      UTF8BytesString.create("java-jdbc-prepared_statement");
  static final CharSequence SQL = UTF8BytesString.create("sql");
  static final String DB_TYPE = "postgresql";
  static final String DB_SERVICE = "postgresql";
  static final String DB_POOL = "HikariPool-1";
  static final String DB_USER = "app";
  static final String DB_INSTANCE = "petclinic";
  static final String DB_HOST = "db.internal";
  static final String DB_OPERATION = "SELECT";
  static final CharSequence STATEMENT = UTF8BytesString.create("SELECT * FROM owners WHERE id = ?");

  // internal: TraceDecorator (@Trace)
  static final String TRACE_INSTRUMENTATION = "trace";
  static final CharSequence TRACE_OPERATION = UTF8BytesString.create("trace.annotation");
  static final CharSequence TRACE_COMPONENT = UTF8BytesString.create("trace");
  static final CharSequence TRACE_RESOURCE = UTF8BytesString.create("OwnerService.findOwner");

  CoreTracer tracer;

  SpanPrototype serverProto;
  SpanPrototype jdbcProto;
  SpanPrototype traceProto;

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
    this.traceProto =
        SpanPrototype.builder()
            .initInstrumentationName(TRACE_INSTRUMENTATION)
            .initOperationName(TRACE_OPERATION)
            .initComponentAndIntegration(TRACE_COMPONENT)
            .build();
  }

  @TearDown
  public void tearDown() {
    this.tracer.close();
  }

  // ---- legacy: every tag by name ----

  @Benchmark
  public void legacy() {
    AgentSpan server = tracer.startSpan(SERVER_INSTRUMENTATION, SERVER_OPERATION);
    server.setSpanType(WEB);
    server.setTag(Tags.COMPONENT, SERVER_COMPONENT);
    server.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    server.setTag("language", "jvm");
    legacyRequest(server);
    try (ContextScope scope = tracer.activateSpan(server)) {
      for (int i = 0; i < JDBC_SPANS; ++i) {
        AgentSpan jdbc = tracer.startSpan(JDBC_INSTRUMENTATION, JDBC_OPERATION);
        jdbc.setSpanType(SQL);
        jdbc.setTag(Tags.COMPONENT, JAVA_JDBC);
        jdbc.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
        legacyJdbc(jdbc);
        finishScoped(jdbc);
      }
      AgentSpan internal = tracer.startSpan(TRACE_INSTRUMENTATION, TRACE_OPERATION);
      internal.setTag(Tags.COMPONENT, TRACE_COMPONENT);
      internal.setResourceName(TRACE_RESOURCE);
      finishScoped(internal);
    }
    server.setHttpStatusCode(STATUS);
    server.finish();
  }

  static void legacyRequest(AgentSpan server) {
    server.setTag(Tags.HTTP_USER_AGENT, USER_AGENT);
    server.setTag(Tags.HTTP_METHOD, METHOD);
    server.setTag(Tags.HTTP_URL, URL);
    server.setTag(Tags.HTTP_HOSTNAME, HOSTNAME);
    server.setResourceName(SERVER_RESOURCE);
    server.setTag(Tags.PEER_HOST_IPV4, CLIENT_IP);
    server.setTag(Tags.PEER_PORT, CLIENT_PORT);
    server.setTag(Tags.HTTP_ROUTE, ROUTE);
  }

  static void legacyJdbc(AgentSpan jdbc) {
    jdbc.setTag(Tags.DB_TYPE, DB_TYPE);
    jdbc.setServiceName(DB_SERVICE, JAVA_JDBC);
    jdbc.setOperationName(JDBC_RENAMED_OPERATION);
    jdbc.setTag(Tags.DB_POOL_NAME, DB_POOL);
    jdbc.setTag(Tags.DB_USER, DB_USER);
    jdbc.setTag(Tags.DB_INSTANCE, DB_INSTANCE);
    jdbc.setTag(Tags.PEER_HOSTNAME, DB_HOST);
    jdbc.setResourceName(STATEMENT);
    jdbc.setTag(Tags.DB_OPERATION, DB_OPERATION);
    jdbc.setTag(Tags.COMPONENT, JDBC_PREPARED_STATEMENT);
  }

  // ---- normal: what this version's decorators do ----

  @Benchmark
  public void normal() {
    // HttpServerDecorator: startSpan, afterStart, onRequest, then onResponseStatus at the end
    AgentSpan server = tracer.startSpan(SERVER_INSTRUMENTATION, SERVER_OPERATION);
    server.applyOverwriting(serverProto); // BaseDecorator.afterStart
    server.setTag(Tags.HTTP_USER_AGENT, USER_AGENT);
    server.setTag(Tags.HTTP_METHOD, METHOD);
    server.setTag(Tags.HTTP_URL, URL);
    server.setTag(Tags.HTTP_HOSTNAME, HOSTNAME);
    server.setResourceName(SERVER_RESOURCE); // HttpResourceDecorator.withServerPath
    server.setTag(Tags.PEER_HOST_IPV4, CLIENT_IP);
    server.setTag(Tags.PEER_PORT, CLIENT_PORT); // setPeerPort
    server.setTag(Tags.HTTP_ROUTE, ROUTE); // the framework, once routing resolves
    try (ContextScope scope = tracer.activateSpan(server)) {
      for (int i = 0; i < JDBC_SPANS; ++i) {
        // AbstractPreparedStatementInstrumentation: startSpan, afterStart, onConnection,
        // onPreparedStatement
        AgentSpan jdbc = tracer.startSpan(JDBC_INSTRUMENTATION, JDBC_OPERATION);
        jdbc.applyOverwriting(jdbcProto);
        jdbc.setTag(Tags.DB_TYPE, DB_TYPE); // processDatabaseType
        jdbc.setServiceName(DB_SERVICE, JAVA_JDBC); // postProcessServiceAndOperationName
        jdbc.setOperationName(JDBC_RENAMED_OPERATION);
        jdbc.setTag(Tags.DB_POOL_NAME, DB_POOL);
        jdbc.setTag(Tags.DB_USER, DB_USER); // DatabaseClientDecorator.onConnection
        jdbc.setTag(Tags.DB_INSTANCE, DB_INSTANCE);
        jdbc.setTag(Tags.PEER_HOSTNAME, DB_HOST);
        jdbc.setResourceName(STATEMENT); // withQueryInfo
        jdbc.setTag(Tags.DB_OPERATION, DB_OPERATION);
        jdbc.spanContext().setIntegrationName(JDBC_PREPARED_STATEMENT);
        jdbc.setTag(Tags.COMPONENT, JDBC_PREPARED_STATEMENT);
        finishScoped(jdbc);
      }
      AgentSpan internal = tracer.startSpan(TRACE_INSTRUMENTATION, TRACE_OPERATION);
      internal.applyOverwriting(traceProto);
      internal.setResourceName(TRACE_RESOURCE);
      finishScoped(internal);
    }
    server.setHttpStatusCode(STATUS); // onResponseStatus
    server.finish();
  }

  // ---- newApi: construction from a prototype, tags by id ----

  @Benchmark
  public void newApi() {
    AgentSpan server = tracer.startSpan(serverProto, SERVER_OPERATION);
    server.setTag(KnownTags.HTTP_USERAGENT_ID, USER_AGENT);
    server.setTag(KnownTags.HTTP_METHOD_ID, METHOD);
    server.setTag(KnownTags.HTTP_URL_ID, URL);
    server.setTag(KnownTags.HTTP_HOSTNAME_ID, HOSTNAME);
    server.setResourceName(SERVER_RESOURCE);
    server.setTag(KnownTags.PEER_IPV4_ID, CLIENT_IP);
    server.setTag(KnownTags.PEER_PORT_ID, CLIENT_PORT);
    server.setTag(KnownTags.HTTP_ROUTE_ID, ROUTE);
    try (ContextScope scope = tracer.activateSpan(server)) {
      for (int i = 0; i < JDBC_SPANS; ++i) {
        AgentSpan jdbc = tracer.startSpan(jdbcProto, JDBC_OPERATION);
        jdbc.setTag(KnownTags.DB_TYPE_ID, DB_TYPE);
        jdbc.setServiceName(DB_SERVICE, JAVA_JDBC);
        jdbc.setOperationName(JDBC_RENAMED_OPERATION);
        jdbc.setTag(KnownTags.DB_POOL_NAME_ID, DB_POOL);
        jdbc.setTag(KnownTags.DB_USER_ID, DB_USER);
        jdbc.setTag(KnownTags.DB_INSTANCE_ID, DB_INSTANCE);
        jdbc.setTag(KnownTags.PEER_HOSTNAME_ID, DB_HOST);
        jdbc.setResourceName(STATEMENT);
        jdbc.setTag(KnownTags.DB_OPERATION_ID, DB_OPERATION);
        jdbc.spanContext().setIntegrationName(JDBC_PREPARED_STATEMENT);
        jdbc.setTag(KnownTags.COMPONENT_ID, JDBC_PREPARED_STATEMENT);
        finishScoped(jdbc);
      }
      AgentSpan internal = tracer.startSpan(traceProto, TRACE_OPERATION);
      internal.setResourceName(TRACE_RESOURCE);
      finishScoped(internal);
    }
    server.setHttpStatusCode(STATUS);
    server.finish();
  }

  /** The advice's exit: activate around the call, then close and finish. */
  void finishScoped(AgentSpan span) {
    ContextScope scope = tracer.activateSpan(span);
    scope.close();
    span.finish();
  }
}
