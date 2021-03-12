package datadog.trace.core;

import datadog.trace.api.DDId;
import java.util.Collections;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 20)
@Measurement(iterations = 5, time = 20)
@BenchmarkMode(Mode.AverageTime)
@Fork(1)
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
    DDId traceId = DDId.from(1);
    trace = tracer.createTrace(traceId);
    root =
        DDSpan.create(
            System.currentTimeMillis() * 1000,
            new DDSpanContext(
                traceId,
                DDId.from(2),
                DDId.ZERO,
                null,
                "service",
                "operation",
                "resource",
                1,
                null,
                Collections.<String, String>emptyMap(),
                false,
                "type",
                0,
                trace));
    span =
        DDSpan.create(
            System.currentTimeMillis() * 1000,
            new DDSpanContext(
                traceId,
                DDId.from(3),
                DDId.from(2),
                null,
                "service",
                "operation",
                "resource",
                1,
                null,
                Collections.<String, String>emptyMap(),
                false,
                "type",
                0,
                trace));
  }

  @Threads(4)
  @Benchmark
  public void writeTraces() {
    trace.registerSpan(root);
    for (int i = 0; i < depthPerThread; ++i) {
      trace.registerSpan(span);
    }
    for (int i = 0; i < depthPerThread; ++i) {
      trace.addFinishedSpan(span);
    }
    trace.addFinishedSpan(root);
  }
}
