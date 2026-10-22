package datadog.trace.core;

import static datadog.context.Context.root;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagators;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
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
 * One whole request per op, through the header code paths: extract the inbound headers with the
 * default propagator, start the server span from what was extracted, run a few children, make one
 * outbound HTTP call that injects its headers, then finish everything.
 *
 * <p>The existing propagation benchmarks each measure one step (extracting into a context,
 * injecting from a prebuilt one, parsing one header value). They can't see costs that the extracted
 * state causes later, on every span of the trace: for example, a {@code tracestate} that leads each
 * span to allocate sampling state. Measuring the whole request catches those.
 *
 * <p>{@code headers} selects the inbound headers, under the default propagation styles:
 *
 * <ul>
 *   <li>{@code none}: a new trace;
 *   <li>{@code datadog}: {@code x-datadog-*}, with propagated tags;
 *   <li>{@code tracecontext}: {@code traceparent} and a {@code tracestate} carrying {@code dd},
 *       OpenTelemetry ({@code ot}) and a third-party member;
 *   <li>{@code both}: Datadog and W3C headers for the same trace, as a Datadog-to-Datadog call
 *       sends them;
 *   <li>{@code bothBaggage}: {@code both}, plus a {@code baggage} header.
 * </ul>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class HeaderPathBenchmark {
  static final int JDBC_SPANS = 3;

  static final String TRACE_ID_HEX = "66a0f6c800000000a3ce929d0e0e4736";
  static final String TRACE_ID_LOW = "11803532876627986230"; // a3ce929d0e0e4736
  static final String PARENT_ID = "67667974448284343"; // 00f067aa0ba902b7
  static final String PARENT_ID_HEX = "00f067aa0ba902b7";

  static final String SERVER_INSTRUMENTATION = "tomcat-server";
  static final CharSequence SERVER_OPERATION = UTF8BytesString.create("servlet.request");
  static final String JDBC_INSTRUMENTATION = "java-jdbc-prepared_statement";
  static final CharSequence JDBC_OPERATION = UTF8BytesString.create("database.query");
  static final String CLIENT_INSTRUMENTATION = "okhttp";
  static final CharSequence CLIENT_OPERATION = UTF8BytesString.create("okhttp.request");

  static final CarrierVisitor<Map<String, String>> GETTER =
      new CarrierVisitor<Map<String, String>>() {
        @Override
        public void forEachKeyValue(
            Map<String, String> carrier, BiConsumer<String, String> visitor) {
          carrier.forEach(visitor);
        }
      };
  static final CarrierSetter<Map<String, String>> SETTER = Map::put;

  @Param({"none", "datadog", "tracecontext", "both", "bothBaggage"})
  String headers;

  CoreTracer tracer;
  Map<String, String> inbound;
  SpanPrototype serverProto;
  SpanPrototype jdbcProto;
  SpanPrototype clientProto;

  /** One outbound carrier per thread, reused across requests. */
  @State(Scope.Thread)
  public static class Outbound {
    final Map<String, String> headers = new HashMap<>();
  }

  @Setup
  public void setup(Blackhole blackhole) {
    this.tracer = CoreTracer.builder().writer(new DropWriter(blackhole)).build();
    this.inbound = inboundHeaders(headers);
    this.serverProto =
        SpanPrototype.builder()
            .initInstrumentationName(SERVER_INSTRUMENTATION)
            .initOperationName(SERVER_OPERATION)
            .initSpanType("web")
            .initComponentAndIntegration("tomcat-server")
            .initKind(Tags.SPAN_KIND_SERVER)
            .initTag("language", "jvm")
            .build();
    this.jdbcProto =
        SpanPrototype.builder()
            .initInstrumentationName(JDBC_INSTRUMENTATION)
            .initOperationName(JDBC_OPERATION)
            .initSpanType("sql")
            .initComponentAndIntegration("java-jdbc")
            .initKind(Tags.SPAN_KIND_CLIENT)
            .build();
    this.clientProto =
        SpanPrototype.builder()
            .initInstrumentationName(CLIENT_INSTRUMENTATION)
            .initOperationName(CLIENT_OPERATION)
            .initSpanType("http")
            .initComponentAndIntegration("okhttp")
            .initKind(Tags.SPAN_KIND_CLIENT)
            .build();
    checkPropagation();
  }

  /**
   * Fails fast if the headers aren't actually extracted and injected, which would measure nothing.
   */
  void checkPropagation() {
    Context parent = Propagators.defaultPropagator().extract(root(), inbound, GETTER);
    if (!headers.equals("none") && fromContext(parent) == null) {
      throw new IllegalStateException("nothing extracted from " + headers + " headers");
    }
    Outbound outbound = new Outbound();
    request(outbound);
    if (!outbound.headers.containsKey("x-datadog-trace-id")) {
      throw new IllegalStateException("nothing injected: " + outbound.headers);
    }
  }

  @TearDown
  public void tearDown() {
    this.tracer.close();
  }

  static Map<String, String> inboundHeaders(String style) {
    Map<String, String> h = new HashMap<>();
    h.put("host", "localhost:8080");
    h.put("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/120.0");
    h.put("accept", "application/json");
    if (style.equals("datadog") || style.startsWith("both")) {
      h.put("x-datadog-trace-id", TRACE_ID_LOW);
      h.put("x-datadog-parent-id", PARENT_ID);
      h.put("x-datadog-sampling-priority", "1");
      h.put("x-datadog-tags", "_dd.p.dm=-0,_dd.p.tid=66a0f6c800000000");
    }
    if (style.equals("tracecontext") || style.startsWith("both")) {
      h.put("traceparent", "00-" + TRACE_ID_HEX + "-" + PARENT_ID_HEX + "-01");
      h.put(
          "tracestate",
          "dd=s:1;p:"
              + PARENT_ID_HEX
              + ";t.dm:-0;t.tid:66a0f6c800000000,"
              + "ot=th:8;rv:9b8233f7e3a151,rojo=00f067aa0ba902b7");
    }
    if (style.equals("bothBaggage")) {
      h.put("baggage", "userId=alice,serverNode=DF%2028,isProduction=false");
    }
    return h;
  }

  @Benchmark
  public void request(Outbound outbound) {
    outbound.headers.clear();
    // HttpServerDecorator.extract / startSpan
    Context parent = Propagators.defaultPropagator().extract(root(), inbound, GETTER);
    AgentSpan extractedSpan = fromContext(parent);
    AgentSpanContext extracted = extractedSpan == null ? null : extractedSpan.spanContext();
    AgentSpan server = tracer.startSpan(SERVER_INSTRUMENTATION, SERVER_OPERATION, extracted);
    server.setMeasured(true);
    server.applyOverwriting(serverProto);
    server.setTag(Tags.HTTP_METHOD, "GET");
    server.setTag(Tags.HTTP_URL, "http://localhost:8080/owners/42");
    server.setTag(Tags.HTTP_ROUTE, "/owners/{ownerId}");
    server.setTag(Tags.PEER_PORT, 54321);

    try (ContextScope serverScope = parent.with(server).attach()) {
      for (int i = 0; i < JDBC_SPANS; ++i) {
        AgentSpan jdbc = tracer.startSpan(JDBC_INSTRUMENTATION, JDBC_OPERATION);
        jdbc.applyOverwriting(jdbcProto);
        jdbc.setTag(Tags.DB_TYPE, "postgresql");
        jdbc.setTag(Tags.DB_INSTANCE, "petclinic");
        jdbc.setTag(Tags.PEER_HOSTNAME, "db.internal");
        jdbc.setResourceName("SELECT * FROM owners WHERE id = ?");
        try (ContextScope s = tracer.activateSpan(jdbc)) {
          // the statement executes
        }
        jdbc.finish();
      }

      // the outbound call: start the client span, inject its headers, finish
      AgentSpan client = tracer.startSpan(CLIENT_INSTRUMENTATION, CLIENT_OPERATION);
      client.applyOverwriting(clientProto);
      client.setTag(Tags.HTTP_METHOD, "GET");
      client.setTag(Tags.HTTP_URL, "http://inventory.internal/items/7");
      client.setTag(Tags.PEER_HOSTNAME, "inventory.internal");
      client.setTag(Tags.PEER_PORT, 8080);
      Propagators.defaultPropagator()
          .inject(Context.current().with(client), outbound.headers, SETTER);
      client.setHttpStatusCode(200);
      client.finish();
    }
    server.setHttpStatusCode(200);
    server.finish();
  }
}
