package datadog.trace.core;

import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.core.propagation.PropagationTags;
import java.util.Properties;
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
 * Per-trace cost of the sampling decision: the propagation-tag bookkeeping (decision maker, Knuth
 * sampling rate, OTel tracestate) that runs on every root decision, even though headers are only
 * written at inject time.
 *
 * <p>Two lenses on the same work:
 *
 * <ul>
 *   <li>{@code span*} arms run a bare root span start → sample → finish, so the decision is
 *       measured in context. Compare them against {@link SpanCreationBenchmark#bareStartSpan},
 *       which samples through the same default sampler.
 *   <li>{@code ptags*} arms make the decision on a fresh {@link PropagationTags} (one per trace, as
 *       in production) with no span around it, so a change to the decision path isn't lost in
 *       span-creation noise.
 * </ul>
 *
 * <p><b>Read the allocation columns.</b> Run with {@code -prof gc}; {@code gc.alloc.rate.norm}
 * (B/op) is deterministic and is the primary signal. Throughput is directional only.
 *
 * <pre>
 *   ./gradlew :dd-trace-core:jmhJar
 *   java -jar dd-trace-core/build/libs/dd-trace-core-*-jmh.jar SamplingDecisionBenchmark -prof gc
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@Threads(8)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class SamplingDecisionBenchmark {
  private static final String INSTRUMENTATION_NAME = "bench";
  private static final String OPERATION_NAME = "servlet.request";

  /** Default sampler with no agent rates: keeps every trace as SAMPLER_KEEP / DEFAULT. */
  CoreTracer defaultSamplerTracer;

  /** Rule sampler at rate 1.0: keeps every trace as USER_KEEP / LOCAL_USER_RULE. */
  CoreTracer ruleSamplerTracer;

  PropagationTags.Factory ptagsFactory;

  @Setup
  public void setup(Blackhole blackhole) {
    defaultSamplerTracer = CoreTracer.builder().writer(new DropWriter(blackhole)).build();

    Properties ruleConfig = new Properties();
    ruleConfig.setProperty(TRACE_SAMPLE_RATE, "1.0");
    // The default limit (100/s) would reject almost every benchmark trace, which skips the keep
    // path entirely.
    ruleConfig.setProperty(TRACE_RATE_LIMIT, String.valueOf(Integer.MAX_VALUE));
    ruleSamplerTracer =
        CoreTracer.builder()
            .writer(new DropWriter(blackhole))
            .sampler(Sampler.Builder.forConfig(ruleConfig))
            .build();

    ptagsFactory = PropagationTags.factory();
  }

  @TearDown
  public void tearDown() {
    defaultSamplerTracer.close();
    ruleSamplerTracer.close();
  }

  /** Root span kept by the default sampler at finish. */
  @Benchmark
  public void spanDefaultSamplerKeep() {
    AgentSpan span = defaultSamplerTracer.startSpan(INSTRUMENTATION_NAME, OPERATION_NAME);
    span.finish();
  }

  /** Root span kept by a sampling rule at finish (probability decision with OTel tracestate). */
  @Benchmark
  public void spanRuleSamplerKeep() {
    AgentSpan span = ruleSamplerTracer.startSpan(INSTRUMENTATION_NAME, OPERATION_NAME);
    span.finish();
  }

  /** Root span kept manually before finish, so the sampler is skipped. */
  @Benchmark
  public void spanManualKeep() {
    AgentSpan span = defaultSamplerTracer.startSpan(INSTRUMENTATION_NAME, OPERATION_NAME);
    span.setSamplingPriority(PrioritySampling.USER_KEEP, SamplingMechanism.MANUAL);
    span.finish();
  }

  /** The probability decision samplers make, on fresh trace-level propagation tags. */
  @Benchmark
  public PropagationTags ptagsProbabilityKeep(ThreadState thread) {
    PropagationTags ptags = ptagsFactory.empty();
    ptags.tryUpdateProbabilitySamplingDecision(
        PrioritySampling.SAMPLER_KEEP,
        SamplingMechanism.AGENT_RATE,
        1.0,
        false,
        thread.nextTraceIdLowOrderBits(),
        false);
    return ptags;
  }

  /**
   * A probability decision followed by the Datadog header, which doesn't read the OTel tracestate.
   */
  @Benchmark
  public String ptagsProbabilityKeepDatadogHeader(ThreadState thread) {
    return ptagsProbabilityKeep(thread).headerValue(PropagationTags.HeaderType.DATADOG);
  }

  /**
   * A probability decision followed by the W3C {@code tracestate} header, which includes the OTel
   * {@code ot} member.
   */
  @Benchmark
  public String ptagsProbabilityKeepW3CHeader(ThreadState thread) {
    return ptagsProbabilityKeep(thread).headerValue(PropagationTags.HeaderType.W3C);
  }

  /** A probability decision followed by the tracestate OTLP export writes, OTel member included. */
  @Benchmark
  public String ptagsProbabilityKeepOtlpTracestate(ThreadState thread) {
    PropagationTags ptags = ptagsProbabilityKeep(thread);
    return ptags.getW3CTracestate(ptags.samplingState());
  }

  /** A priority-only decision (manual keep), on fresh trace-level propagation tags. */
  @Benchmark
  public PropagationTags ptagsPriorityKeep() {
    PropagationTags ptags = ptagsFactory.empty();
    ptags.tryUpdateTraceSamplingPriority(
        PrioritySampling.USER_KEEP, SamplingMechanism.MANUAL, false);
    return ptags;
  }

  /** Varies the trace id so the OTel random value isn't a constant. */
  @State(Scope.Thread)
  public static class ThreadState {
    long traceIdLowOrderBits = 0x5DEECE66DL;

    long nextTraceIdLowOrderBits() {
      traceIdLowOrderBits = traceIdLowOrderBits * 6364136223846793005L + 1442695040888963407L;
      return traceIdLowOrderBits;
    }
  }
}
