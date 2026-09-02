package datadog.trace.core.monitor;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.common.writer.RemoteApi;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Direct measurement of the real {@link TracerHealthMetrics} entry points hit on the tracing hot
 * path -- the {@link datadog.trace.util.Accumulator}-backed implementation, not a synthetic
 * stand-in -- so the {@code AccumulatorBenchmark} numbers (raw {@code Accumulator} vs {@code
 * LongAdder}) can be checked against what the real per-call-site shapes cost once real switches,
 * multi-counter updates, and response-as-context dispatch are involved. All calls go through {@link
 * StatsDClient#NO_OP} so only the accumulator-side cost is measured, not statsd transport.
 *
 * <p>{@code onCreateSpan}/{@code onFinishSpan}/{@code onActivateScope}/{@code onCloseScope} are the
 * highest-frequency calls (once per span/scope) and are each a single {@code inc()}. {@code
 * onFailedPublish} exercises a switch plus two independent (ungrouped) counter updates. {@code
 * onPartialPublish} exercises the boxing-free {@code update(long, ObjLongConsumer)} grouped-update
 * path. {@code onSend} exercises {@code onSendAttempt}'s split shape: three top-level calls plus
 * one {@code update(response, ...)} grouping the two response-derived counters under one lock.
 *
 * <p>{@code summaryWhileWriting} pairs concurrent {@code onCreateSpan} writers against a single
 * reader repeatedly calling {@code summary()} -- {@code summary()} only peeks ({@code
 * Accumulator#sum()}), never drains, so it should not stall the periodic {@code Flush} task nor
 * meaningfully slow down concurrent writers; this checks that design assumption under load rather
 * than just asserting it.
 *
 * <p><b>Before/after.</b> The {@code legacy*} benchmarks run the identical inputs against {@link
 * LegacyTracerHealthMetrics}, a faithful reconstruction of pre-migration {@code
 * TracerHealthMetrics} (one {@code LongAdder} field per counter, hand-rolled switches, a
 * hand-concatenated {@code summary()}) as it stood at {@code 77964b3996}, the last commit before
 * this migration -- a same-run, same-JVM before/after comparison of the real class, not just the
 * underlying primitive.
 *
 * <p><b>Results.</b> After {@link datadog.trace.util.Accumulator}'s lock-free {@code
 * AtomicLongArray}-striping rewrite, every hot single-counter call (uncontended) lands at
 * 0.007-0.009 us/op -- indistinguishable from {@code AccumulatorBenchmark}'s raw {@code
 * accumulatorIncrement_lowContention} (0.007 us/op). At {@code Threads.MAX} the real call sites
 * stay just as flat (0.009-0.013 us/op): the CAS-based {@code getAndAdd} no longer pays the
 * 3-4x {@code synchronized}-stripe contention penalty the earlier design did. {@code
 * summaryWhileWriting} confirms the peek-not-drain design for {@code summary()}: concurrent
 * readers don't measurably slow writers (0.008 us/op, same as uncontended {@code onCreateSpan}),
 * at the cost of the read itself walking all 54 stripes non-destructively (~1.83 us/op) --
 * acceptable for a diagnostic/tracer-flare call, never on the span-emission path. Results are
 * stable across JDK 17 and JDK 25 (point estimates agree to the millisecond-precision printed
 * below), confirming this is the striping rewrite's effect, not a JIT/JVM-version artifact.
 * <code>
 * Apple M1 Max, 10 CPUs - macOS/aarch64 - JDK 17 (Zulu) / JDK 25 (Zulu)
 * Benchmark                                                JDK17  JDK25  Units
 * TracerHealthMetricsBenchmark.onCreateSpan_lowContention   0.007  0.007  us/op
 * TracerHealthMetricsBenchmark.onCreateSpan_highContention  0.009  0.009  us/op
 * TracerHealthMetricsBenchmark.onFailedPublish_lowContention   0.007  0.007  us/op
 * TracerHealthMetricsBenchmark.onFailedPublish_highContention  0.010  0.010  us/op
 * TracerHealthMetricsBenchmark.onPartialPublish_lowContention  0.007  0.007  us/op
 * TracerHealthMetricsBenchmark.onPartialPublish_highContention 0.009  0.010  us/op
 * TracerHealthMetricsBenchmark.onSend_lowContention         0.009  0.009  us/op
 * TracerHealthMetricsBenchmark.onSend_highContention        0.013  0.013  us/op
 * TracerHealthMetricsBenchmark.summaryWhileWriting                0.373  0.373  us/op
 * TracerHealthMetricsBenchmark.summaryWhileWriting:...write        0.008  0.008  us/op
 * TracerHealthMetricsBenchmark.summaryWhileWriting:...read         1.835  1.833  us/op
 * </code>
 *
 * <p><b>Before/after results.</b> The {@code Accumulator}-backed implementation is now at parity
 * with, or measurably <em>faster</em> than, the {@code LongAdder} baseline it replaced on every
 * single-call-site benchmark, including under {@code Threads.MAX} contention -- a reversal of the
 * earlier {@code synchronized}-stripe design's 1.1-4.6x cost documented before the lock-free
 * rewrite (commit {@code 3ea84793d1}). {@code onSend}, the most expensive real call site (three
 * top-level calls plus one two-counter grouped update), now costs 0.6-0.75x of legacy at both
 * contention levels. {@code summary()} remains the one real cost: walking 54 stripes
 * non-destructively still costs ~2.5-2.6x what summing 52 plain {@code LongAdder} fields does
 * (~1.83 vs ~0.71 us/op) -- still far below the periodic (30s-default) {@code Flush} cadence and
 * the ad hoc/diagnostic calls that trigger it, so not disqualifying. Neither implementation's
 * writers are measurably slowed by a concurrent {@code summary()}/reader. <code>
 * Apple M1 Max, 10 CPUs - macOS/aarch64 - JDK 17 (Zulu) / JDK 25 (Zulu)
 * Benchmark                       New (JDK17/25)  Legacy (JDK17/25)  Ratio
 * onCreateSpan_lowContention        0.007 / 0.007    0.007 / 0.007    1.0x / 1.0x
 * onCreateSpan_highContention       0.009 / 0.009    0.010 / 0.010    0.9x / 0.9x
 * onFailedPublish_lowContention     0.007 / 0.007    0.008 / 0.008    0.9x / 0.9x
 * onFailedPublish_highContention    0.010 / 0.010    0.011 / 0.012    0.9x / 0.8x
 * onPartialPublish_lowContention    0.007 / 0.007    0.008 / 0.008    0.9x / 0.9x
 * onPartialPublish_highContention   0.009 / 0.010    0.012 / 0.012    0.75x / 0.8x
 * onSend_lowContention              0.009 / 0.009    0.012 / 0.013    0.75x / 0.7x
 * onSend_highContention             0.013 / 0.013    0.020 / 0.022    0.65x / 0.6x
 * summaryWhileWriting_write         0.008 / 0.008    0.009 / 0.008    0.9x / 1.0x
 * summaryWhileWriting_read          1.835 / 1.833    0.705 / 0.720    2.6x / 2.55x
 * (all figures us/op, avgt; JDK17 / JDK25)
 * </code> This means the migration's case no longer rests solely on eliminating the {@code
 * previousCounts}/{@code countIndex} hand-tracking ceremony and giving each counter an atomic
 * multi-field grouped update -- the lock-free rewrite makes it a speedup too, on every path except
 * the diagnostic {@code summary()} read.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 3, time = 10)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(2)
public class TracerHealthMetricsBenchmark {

  private final TracerHealthMetrics metrics = new TracerHealthMetrics(StatsDClient.NO_OP);
  private final LegacyTracerHealthMetrics legacyMetrics =
      new LegacyTracerHealthMetrics(StatsDClient.NO_OP);
  private final RemoteApi.Response okResponse = RemoteApi.Response.success(200);

  @Benchmark
  @Threads(1)
  public void onCreateSpan_lowContention() {
    metrics.onCreateSpan();
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void onCreateSpan_highContention() {
    metrics.onCreateSpan();
  }

  @Benchmark
  @Threads(1)
  public void onFailedPublish_lowContention() {
    metrics.onFailedPublish(SAMPLER_DROP, 5);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void onFailedPublish_highContention() {
    metrics.onFailedPublish(SAMPLER_DROP, 5);
  }

  @Benchmark
  @Threads(1)
  public void onPartialPublish_lowContention() {
    metrics.onPartialPublish(3);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void onPartialPublish_highContention() {
    metrics.onPartialPublish(3);
  }

  @Benchmark
  @Threads(1)
  public void onSend_lowContention() {
    metrics.onSend(1, 512, okResponse);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void onSend_highContention() {
    metrics.onSend(1, 512, okResponse);
  }

  @Benchmark
  @Group("summaryWhileWriting")
  @GroupThreads(4)
  public void summaryWhileWriting_write() {
    metrics.onCreateSpan();
  }

  @Benchmark
  @Group("summaryWhileWriting")
  @GroupThreads(1)
  public void summaryWhileWriting_read(Blackhole blackhole) {
    blackhole.consume(metrics.summary());
  }

  @Benchmark
  @Threads(1)
  public void legacyOnCreateSpan_lowContention() {
    legacyMetrics.onCreateSpan();
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void legacyOnCreateSpan_highContention() {
    legacyMetrics.onCreateSpan();
  }

  @Benchmark
  @Threads(1)
  public void legacyOnFailedPublish_lowContention() {
    legacyMetrics.onFailedPublish(SAMPLER_DROP, 5);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void legacyOnFailedPublish_highContention() {
    legacyMetrics.onFailedPublish(SAMPLER_DROP, 5);
  }

  @Benchmark
  @Threads(1)
  public void legacyOnPartialPublish_lowContention() {
    legacyMetrics.onPartialPublish(3);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void legacyOnPartialPublish_highContention() {
    legacyMetrics.onPartialPublish(3);
  }

  @Benchmark
  @Threads(1)
  public void legacyOnSend_lowContention() {
    legacyMetrics.onSend(1, 512, okResponse);
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void legacyOnSend_highContention() {
    legacyMetrics.onSend(1, 512, okResponse);
  }

  @Benchmark
  @Group("legacySummaryWhileWriting")
  @GroupThreads(4)
  public void legacySummaryWhileWriting_write() {
    legacyMetrics.onCreateSpan();
  }

  @Benchmark
  @Group("legacySummaryWhileWriting")
  @GroupThreads(1)
  public void legacySummaryWhileWriting_read(Blackhole blackhole) {
    blackhole.consume(legacyMetrics.summary());
  }
}
