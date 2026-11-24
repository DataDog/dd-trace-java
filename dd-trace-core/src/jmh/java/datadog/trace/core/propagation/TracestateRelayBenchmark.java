package datadog.trace.core.propagation;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.core.propagation.ptags.W3CTracestate;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Relaying a trace that arrived with both header formats, as a Kafka consume-then-produce service
 * does for every record: the Datadog headers become the trace's propagation tags, the {@code
 * tracestate} is merged in at lower priority, and the outbound span writes a new {@code tracestate}
 * with its own parent id. One op is one relayed trace, so nothing is cached across ops.
 *
 * <p>{@code tracestate} selects the inbound header: {@code ddOnly} has just the {@code dd} member;
 * {@code withOtel} adds the OpenTelemetry {@code ot} member a Datadog tracer writes since 1.67,
 * which is written back unparsed after checking it is consistent with the sampling decision.
 *
 * <p>Read ns/op and {@code gc.alloc.rate.norm}:
 *
 * <pre>
 *   ./gradlew :dd-trace-core:jmh -Pjmh.includes=TracestateRelayBenchmark -Pjmh.profilers=gc
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class TracestateRelayBenchmark {
  static final String DATADOG_TAGS = "_dd.p.dm=-1,_dd.p.tid=66a0f6c800000000";
  static final String OUTBOUND_PARENT = "0f3d1e8a77b2c9d4";

  @Param({"ddOnly", "withOtel"})
  String tracestate;

  PropagationTags.Factory factory;
  String tracestateHeader;

  @Setup
  public void setUp() {
    factory = PropagationTags.factory();
    tracestateHeader =
        "withOtel".equals(tracestate)
            ? "dd=s:1;p:00f067aa0ba902b7;t.dm:-1;t.tid:66a0f6c800000000,ot=th:0;rv:a1b2c3d4e5f607"
            : "dd=s:1;p:00f067aa0ba902b7;t.dm:-1;t.tid:66a0f6c800000000";
  }

  @Benchmark
  public String relay() {
    PropagationTags ptags =
        factory.fromDatadogHeaderValue(DATADOG_TAGS, PrioritySampling.SAMPLER_KEEP);
    ptags.updateW3CTracestateFrom(
        W3CTracestate.parseForLowerPriorityMerge(tracestateHeader), PrioritySampling.UNSET);
    return ptags.headerValue(
        PropagationTags.HeaderType.W3C, OUTBOUND_PARENT, ptags.samplingState());
  }
}
