package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.Writer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
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
 * End-to-end JMH benchmark of a 3-span HTTP-style trace through {@link CoreTracer}: one {@code
 * span.kind=server} root + two {@code span.kind=client} children, as if a service handled an
 * incoming request that made two outbound HTTP calls. Children inherit the server span as parent
 * via implicit scope-based parentage; the root finishes last so {@code PendingTrace.write} ->
 * {@code tracer.write(trace)} -> metricsAggregator.publish + writer.write (no-op) runs
 * synchronously on the producing thread.
 *
 * <p>Runs multi-threaded ({@link Threads} = 8 by default; override with {@code -t N}) so the
 * allocation rate {@code -prof gc} reports reflects multiple producers hitting the shared
 * metrics aggregator + writer pipeline, and so we can compare total throughput between revisions.
 *
 * <p>Reflection is used to swap the tracer's default no-op {@code metricsAggregator} for a real
 * {@link ClientStatsAggregator} so the metrics pipeline actually runs.
 *
 * <p>Two modes via {@code @Param}:
 *
 * <ul>
 *   <li>{@code stable} -- every op uses the same labels (cache-hit path on the consumer).
 *   <li>{@code varied} -- every op uses unique service / operation / resource per span (miss path
 *       until cardinality budgets fill, then sentinel collapse).
 * </ul>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 15, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 15, timeUnit = SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Threads(8)
@Fork(value = 2)
public class TracePipelineBenchmark {

  @Param({"stable", "varied"})
  String mode;

  private CoreTracer tracer;
  private ClientStatsAggregator aggregator;
  private boolean stable;

  @State(Scope.Thread)
  public static class ThreadState {
    int cursor;
  }

  @Setup
  public void setup() throws Exception {
    this.stable = "stable".equals(mode);
    this.tracer = CoreTracer.builder().writer(new NoopWriter()).strictTraceWrites(false).build();
    this.aggregator =
        new ClientStatsAggregator(
            new WellKnownTags("", "", "", "", "", ""),
            Collections.emptySet(),
            new ClientStatsAggregatorBenchmark.FixedAgentFeaturesDiscovery(
                Collections.singleton("peer.hostname"), Collections.emptySet()),
            HealthMetrics.NO_OP,
            new ClientStatsAggregatorBenchmark.NullSink(),
            2048,
            2048,
            false);
    this.aggregator.start();
    // Replace the no-op aggregator the tracer was constructed with. The field is package-private
    // in datadog.trace.core; reflect since this benchmark lives in the metrics package.
    Field f = CoreTracer.class.getDeclaredField("metricsAggregator");
    f.setAccessible(true);
    f.set(this.tracer, this.aggregator);
  }

  @TearDown
  public void tearDown() {
    aggregator.close();
    tracer.close();
  }

  @Benchmark
  public void threeSpanTrace(ThreadState ts, Blackhole blackhole) {
    int idx = ts.cursor++;
    String service = stable ? "svc" : "svc-" + idx;
    String serverOp = stable ? "servlet.request" : "servlet.request-" + idx;
    String serverResource = stable ? "GET /widgets/{id}" : "GET /widgets/" + idx;
    String clientOp = stable ? "http.request" : "http.request-" + idx;
    String clientResource1 = stable ? "GET /downstream-a" : "GET /downstream-a/" + idx;
    String clientResource2 = stable ? "GET /downstream-b" : "GET /downstream-b/" + idx;
    String hostA = stable ? "host-a" : "host-a-" + idx;
    String hostB = stable ? "host-b" : "host-b-" + idx;

    AgentSpan server = tracer.startSpan("servlet", serverOp);
    server.setResourceName(serverResource);
    server.setServiceName(service);
    server.setTag(SPAN_KIND, SPAN_KIND_SERVER);
    AgentScope serverScope = tracer.activateSpan(server);
    try {
      AgentSpan client1 = tracer.startSpan("okhttp", clientOp);
      client1.setResourceName(clientResource1);
      client1.setServiceName(service);
      client1.setTag(SPAN_KIND, SPAN_KIND_CLIENT);
      client1.setTag("peer.hostname", hostA);
      AgentScope client1Scope = tracer.activateSpan(client1);
      try {
        // simulated unit of in-call work would go here
      } finally {
        client1Scope.close();
      }
      client1.finish();

      AgentSpan client2 = tracer.startSpan("okhttp", clientOp);
      client2.setResourceName(clientResource2);
      client2.setServiceName(service);
      client2.setTag(SPAN_KIND, SPAN_KIND_CLIENT);
      client2.setTag("peer.hostname", hostB);
      AgentScope client2Scope = tracer.activateSpan(client2);
      try {
        // simulated unit of in-call work would go here
      } finally {
        client2Scope.close();
      }
      client2.finish();
    } finally {
      serverScope.close();
    }
    // Finishing the root last triggers PendingTrace.write -> tracer.write -> metrics + writer on
    // this thread, since all child refs have already decremented to zero.
    server.finish();
    blackhole.consume(server);
  }

  private static final class NoopWriter implements Writer {
    @Override
    public void write(List<DDSpan> trace) {}

    @Override
    public void start() {}

    @Override
    public boolean flush() {
      return true;
    }

    @Override
    public void close() {}

    @Override
    public void incrementDropCounts(int spanCount) {}
  }
}
