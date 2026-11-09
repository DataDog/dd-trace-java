package datadog.trace.core;

import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagators;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.common.sampling.Sampler;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
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
 * Per-request tracing cost of the standard propagation scenarios, with a default-configured tracer
 * (default propagation styles: Datadog, W3C trace context and baggage). It puts a cost on the
 * per-trace sampling and propagation bookkeeping, such as the OpenTelemetry {@code ot} tracestate
 * member that v1.67.0 started generating, as a service actually sees it.
 *
 * <p>This is a <b>scenario benchmark</b>: each arm is a whole request as a service handles it,
 * driven through the tracer's public API, rather than one method in isolation. A change to any
 * piece shows up as what it costs a real request, and the result doesn't depend on which internals
 * the benchmark happens to call. The scenarios:
 *
 * <ul>
 *   <li>{@link #headNoPropagation}: a root service span that makes the sampling decision (at
 *       finish) and calls nothing downstream.
 *   <li>{@link #headPropagation}: a root service span plus an outbound client span that injects, so
 *       the decision is made at injection and written into the headers.
 *   <li>{@link #leaf}: extracts an upstream decision and calls nothing downstream.
 *   <li>{@link #intermediate}: extracts an upstream decision and relays it through an outbound
 *       client span.
 * </ul>
 *
 * <p>{@code upstream} selects what the extracting scenarios receive: {@code sameVersion} is what
 * this tracer itself injects (a fleet on one version), and {@code withOtelMember} is a fixed
 * current format carrying an {@code ot} member (an upgraded upstream, whatever this tracer's
 * version). Upstream headers cycle through 1024 distinct trace ids so caches keyed by header
 * content see realistic variety.
 *
 * <p><b>Read the allocation columns.</b> Run with {@code -prof gc}; {@code gc.alloc.rate.norm}
 * (B/op) is the primary signal. Throughput is directional only.
 *
 * <p><b>Drift-stable so it can be built onto past release tags</b> (e.g. v1.66.0, the last release
 * before the {@code ot} member was generated). It uses only the {@code datadog.context} propagation
 * API, {@link CoreTracer} builder/span API, {@link Sampler.Builder#forConfig} and {@link
 * DropWriter}, all unchanged from v1.66.0 to master. Keep it inside that surface.
 *
 * <pre>
 *   ./gradlew :dd-trace-core:jmhJar
 *   java -jar dd-trace-core/build/libs/dd-trace-core-*-jmh.jar PropagationScenariosBenchmark -prof gc
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class PropagationScenariosBenchmark {
  private static final String INSTRUMENTATION_NAME = "bench";
  private static final String SERVER_OPERATION_NAME = "servlet.request";
  private static final String CLIENT_OPERATION_NAME = "http.request";
  private static final int UPSTREAM_TRACE_COUNT = 1024;
  // 128-bit trace ids carry their creation time in the top 32 bits, the rest of the high half zero
  private static final long TRACE_ID_EPOCH_SECONDS = 1_760_000_000L;

  /** Injects into the benchmark's blackhole, so header values are consumed without a map. */
  private static final CarrierSetter<Blackhole> BLACKHOLE_SETTER =
      (blackhole, key, value) -> blackhole.consume(value);

  private static final CarrierVisitor<Map<String, String>> MAP_VISITOR = Map::forEach;

  /**
   * {@code default}: the default sampler with no agent rates, as most services run. {@code rule}: a
   * configured sample rate (1.0, limit raised so the benchmark isn't rate limited).
   */
  @Param({"default", "rule"})
  String sampler;

  CoreTracer tracer;

  @Setup
  public void setup(Blackhole blackhole) {
    CoreTracer.CoreTracerBuilder builder = CoreTracer.builder().writer(new DropWriter(blackhole));
    if ("rule".equals(sampler)) {
      Properties ruleConfig = new Properties();
      ruleConfig.setProperty(TRACE_SAMPLE_RATE, "1.0");
      ruleConfig.setProperty(TRACE_RATE_LIMIT, String.valueOf(Integer.MAX_VALUE));
      builder.sampler(Sampler.Builder.forConfig(ruleConfig));
    }
    // Building the tracer registers its propagators as the default ones.
    tracer = builder.build();
  }

  @TearDown
  public void tearDown() {
    tracer.close();
  }

  @Benchmark
  public void headNoPropagation() {
    AgentSpan server = tracer.buildSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME).start();
    server.finish();
  }

  @Benchmark
  public void headPropagation(Blackhole blackhole) {
    AgentSpan server = tracer.buildSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME).start();
    callDownstream(server, blackhole);
    server.finish();
  }

  @Benchmark
  public void leaf(Upstream upstream, Cursor cursor) {
    AgentSpan server =
        tracer
            .buildSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME)
            .asChildOf(extract(upstream.headers[cursor.next()]))
            .start();
    server.finish();
  }

  @Benchmark
  public void intermediate(Upstream upstream, Cursor cursor, Blackhole blackhole) {
    AgentSpan server =
        tracer
            .buildSpan(INSTRUMENTATION_NAME, SERVER_OPERATION_NAME)
            .asChildOf(extract(upstream.headers[cursor.next()]))
            .start();
    callDownstream(server, blackhole);
    server.finish();
  }

  private void callDownstream(AgentSpan server, Blackhole blackhole) {
    AgentSpan client =
        tracer
            .buildSpan(INSTRUMENTATION_NAME, CLIENT_OPERATION_NAME)
            .asChildOf(server.spanContext())
            .start();
    Propagators.defaultPropagator().inject(client, blackhole, BLACKHOLE_SETTER);
    client.finish();
  }

  private static AgentSpanContext extract(Map<String, String> headers) {
    Context extracted =
        Propagators.defaultPropagator().extract(Context.root(), headers, MAP_VISITOR);
    return AgentSpan.fromContext(extracted).spanContext();
  }

  /** The incoming headers the extracting scenarios cycle through. */
  @State(Scope.Benchmark)
  public static class Upstream {
    @Param({"sameVersion", "withOtelMember"})
    String upstream;

    @SuppressWarnings("unchecked")
    final Map<String, String>[] headers = new Map[UPSTREAM_TRACE_COUNT];

    @Setup
    public void setup(PropagationScenariosBenchmark benchmark) {
      Random random = new Random(42);
      for (int i = 0; i < UPSTREAM_TRACE_COUNT; ++i) {
        headers[i] =
            "sameVersion".equals(upstream)
                ? injectedBy(benchmark.tracer)
                : currentFormat(
                    random.nextLong() | 1,
                    (TRACE_ID_EPOCH_SECONDS + i) << 32,
                    random.nextLong() | 1);
      }
    }

    private static Map<String, String> injectedBy(CoreTracer tracer) {
      Map<String, String> headers = new HashMap<>();
      AgentSpan span = tracer.buildSpan(INSTRUMENTATION_NAME, CLIENT_OPERATION_NAME).start();
      Propagators.defaultPropagator().inject(span, headers, Map::put);
      span.finish();
      return headers;
    }

    /** What a current Datadog tracer sends after a default-sampler keep. */
    private static Map<String, String> currentFormat(
        long traceIdLow, long traceIdHigh, long parentId) {
      String tid = hex16(traceIdHigh);
      String parent = hex16(parentId);
      Map<String, String> headers = new HashMap<>();
      headers.put("x-datadog-trace-id", Long.toUnsignedString(traceIdLow));
      headers.put("x-datadog-parent-id", Long.toUnsignedString(parentId));
      headers.put("x-datadog-sampling-priority", "1");
      headers.put("x-datadog-tags", "_dd.p.dm=-1,_dd.p.tid=" + tid + ",_dd.p.ksr=1");
      headers.put("traceparent", "00-" + tid + hex16(traceIdLow) + "-" + parent + "-01");
      headers.put(
          "tracestate",
          "dd=s:1;p:"
              + parent
              + ";t.dm:-1;t.tid:"
              + tid
              + ";t.ksr:1,ot=rv:"
              + hex16(traceIdLow).substring(2)
              + ";th:0");
      return headers;
    }

    private static String hex16(long value) {
      String hex = Long.toHexString(value);
      return "0000000000000000".substring(hex.length()) + hex;
    }
  }

  /** Per-thread position in the upstream headers. */
  @State(Scope.Thread)
  public static class Cursor {
    int index;

    int next() {
      index = (index + 1) & (UPSTREAM_TRACE_COUNT - 1);
      return index;
    }
  }
}
