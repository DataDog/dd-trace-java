package datadog.trace.core;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopPathwayContext;
import java.util.Collections;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class PendingTraceWrite {

  CoreTracer tracer;
  PendingTrace trace;

  @Param({"10", "100"})
  int depthPerThread;

  @Param({"0", "5", "10"})
  int tokens;

  private DDSpan root;
  private DDSpan span;

  @Setup(Level.Trial)
  public void init(TraceCounters counters, Blackhole blackhole) {
    tracer =
        CoreTracer.builder()
            .writer(new BlackholeWriter(blackhole, counters, tokens))
            .strictTraceWrites(false)
            .build();
    DDTraceId traceId = DDTraceId.ONE;
    trace = tracer.createTrace(traceId);
    root =
        DDSpan.create(
            "benchmark",
            System.currentTimeMillis() * 1000,
            new DDSpanContext(
                traceId,
                2,
                DDSpanId.ZERO,
                null,
                "service",
                "operation",
                "resource",
                PrioritySampling.SAMPLER_KEEP,
                null,
                Collections.<String, String>emptyMap(),
                false,
                "type",
                0,
                trace,
                null,
                null,
                NoopPathwayContext.INSTANCE,
                false,
                null));
    span =
        DDSpan.create(
            "benchmark",
            System.currentTimeMillis() * 1000,
            new DDSpanContext(
                traceId,
                3,
                2,
                null,
                "service",
                "operation",
                "resource",
                PrioritySampling.SAMPLER_KEEP,
                null,
                Collections.<String, String>emptyMap(),
                false,
                "type",
                0,
                trace,
                null,
                null,
                NoopPathwayContext.INSTANCE,
                false,
                null));
  }

  @Threads(4)
  @Benchmark
  public void writeTraces() {
    trace.registerSpan(root);
    for (int i = 0; i < depthPerThread; ++i) {
      trace.registerSpan(span);
    }
    for (int i = 0; i < depthPerThread; ++i) {
      trace.onPublish(span);
    }
    trace.onPublish(root);
  }
}
