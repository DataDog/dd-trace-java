package datadog.trace.core;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
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
  TraceCollector traceCollector;

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
    traceCollector = tracer.createTraceCollector(traceId);
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
                traceCollector,
                null,
                null,
                NoopPathwayContext.INSTANCE,
                false,
                null),
            null);
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
                traceCollector,
                null,
                null,
                NoopPathwayContext.INSTANCE,
                false,
                null),
            null);
  }

  @Threads(4)
  @Benchmark
  public void writeTraces() {
    traceCollector.registerSpan(root);
    for (int i = 0; i < depthPerThread; ++i) {
      traceCollector.registerSpan(span);
    }
    for (int i = 0; i < depthPerThread; ++i) {
      traceCollector.onPublish(span);
    }
    traceCollector.onPublish(root);
  }
}
