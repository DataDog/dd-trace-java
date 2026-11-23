package datadog.trace.core.propagation;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.core.propagation.ptags.W3CTracestate;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The parts of merging a W3C trace context into a Datadog-extracted one, on the trace context
 * headers a Datadog producer writes (as on the Kafka benchmark's records).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class W3CMergeParseBenchmark {
  String traceparent = "00-6afeff9a0000000038aa630b3d735025-79ccabfbeb4c036c-01";
  String tracestate =
      "dd=s:2;p:79ccabfbeb4c036c;t.dm:-3;t.tid:6afeff9a00000000;t.ksr:1,ot=rv:9bc92facc1a85e;th:0";
  String tracestateDatadogOnly = "dd=s:2;p:79ccabfbeb4c036c;t.dm:-3;t.tid:6afeff9a00000000;t.ksr:1";

  @Benchmark
  public void traceparent(Blackhole bh) {
    W3CHttpCodec.validateTraceParent(traceparent);
    bh.consume(W3CHttpCodec.traceParentTraceId(traceparent));
    bh.consume(W3CHttpCodec.traceParentSpanId(traceparent));
    bh.consume(W3CHttpCodec.traceParentPriority(traceparent));
  }

  @Benchmark
  public W3CTracestate tracestateFull() {
    return W3CTracestate.parse(tracestate);
  }

  @Benchmark
  public W3CTracestate tracestateForLowerPriorityMerge() {
    return W3CTracestate.parseForLowerPriorityMerge(tracestate);
  }

  @Benchmark
  public W3CTracestate tracestateFullWithoutOtelMember() {
    return W3CTracestate.parse(tracestateDatadogOnly);
  }
}
