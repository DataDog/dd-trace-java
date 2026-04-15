package datadog.trace.core.propagation.ptags;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.propagation.PropagationTags;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmarks for the full PropagationTags lifecycle: creation, sampling decision, Knuth sampling
 * rate update, and header generation.
 *
 * <p>Models the per-trace hot path: {@code empty()} -> {@code updateTraceSamplingPriority()} ->
 * {@code updateKnuthSamplingRate()} -> {@code headerValue()}.
 *
 * <p>Run with:
 *
 * <pre>
 *   ./gradlew :dd-trace-core:jmhJar
 *   java -jar dd-trace-core/build/libs/dd-trace-core-*-jmh.jar PropagationTagsLifecycleBenchmark \
 *     -prof gc
 * </pre>
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 5, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Threads(1)
@Fork(value = 1)
public class PropagationTagsLifecycleBenchmark {

  @Param({"0", "1", "3", "4", "5"})
  int samplingMechanism;

  private PropagationTags.Factory factory;

  @Setup(Level.Trial)
  public void setUp() {
    factory = PropagationTags.factory();
  }

  /**
   * Baseline: create a fresh empty PTags. This is what every trace root starts with. Should be very
   * cheap (constructor only).
   */
  @Benchmark
  public void createEmpty(Blackhole bh) {
    bh.consume(factory.empty());
  }

  /**
   * Create + sampling decision. Tests the decision maker TagValue lookup -- pre-cached array for
   * known mechanisms should eliminate the "-" + mechanism String concatenation.
   */
  @Benchmark
  public void createAndSetSamplingPriority(Blackhole bh) {
    PropagationTags pt = factory.empty();
    pt.updateTraceSamplingPriority(1, samplingMechanism);
    bh.consume(pt);
  }

  /**
   * Create + sampling decision + Knuth sampling rate. Tests the full per-trace hot path including
   * the cached KSR TagValue.
   */
  @Benchmark
  public void createSetPriorityAndKnuthRate(Blackhole bh) {
    PropagationTags pt = factory.empty();
    pt.updateTraceSamplingPriority(1, samplingMechanism);
    pt.updateKnuthSamplingRate(0.5);
    bh.consume(pt);
  }

  /**
   * Full lifecycle: create, set sampling, set Knuth rate, then generate DATADOG header. This is the
   * complete per-trace hot path.
   */
  @Benchmark
  public void fullLifecycleDatadog(Blackhole bh) {
    PropagationTags pt = factory.empty();
    pt.updateTraceSamplingPriority(1, samplingMechanism);
    pt.updateKnuthSamplingRate(0.5);
    bh.consume(pt.headerValue(PropagationTags.HeaderType.DATADOG));
  }

  /**
   * Full lifecycle with W3C header generation. Tests the complete path for W3C propagation
   * including tracestate header encoding.
   */
  @Benchmark
  public void fullLifecycleW3C(Blackhole bh) {
    PropagationTags pt = factory.empty();
    pt.updateTraceSamplingPriority(1, samplingMechanism);
    pt.updateKnuthSamplingRate(0.5);
    bh.consume(pt.headerValue(PropagationTags.HeaderType.W3C));
  }

  /**
   * Repeated sampling decision on the same PTags with same mechanism. The clearCachedHeader
   * null-check optimization should short-circuit on fresh PTags (no header cache allocated yet).
   */
  @Benchmark
  public void repeatedSamplingDecision(Blackhole bh) {
    PropagationTags pt = factory.empty();
    // First call sets the decision maker
    pt.updateTraceSamplingPriority(1, samplingMechanism);
    // Second call with same value -- tests the equals() fast path
    pt.updateTraceSamplingPriority(1, samplingMechanism);
    bh.consume(pt);
  }

  /**
   * Models the EXTERNAL_OVERRIDE path (e.g., incoming W3C traceparent). EXTERNAL_OVERRIDE is mapped
   * to DEFAULT mechanism internally.
   */
  @Benchmark
  public void externalOverride(Blackhole bh) {
    PropagationTags pt = factory.empty();
    pt.updateTraceSamplingPriority(1, SamplingMechanism.EXTERNAL_OVERRIDE);
    bh.consume(pt);
  }
}
