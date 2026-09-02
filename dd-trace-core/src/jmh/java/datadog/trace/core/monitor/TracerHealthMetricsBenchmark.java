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
 * <p><b>Results:</b> every hot single-counter call (uncontended) lands at 0.009-0.018 us/op --
 * indistinguishable from {@code AccumulatorBenchmark}'s raw {@code
 * accumulatorIncrement_lowContention} (0.010 us/op), confirming the switch/branch dispatch around
 * each call site costs nothing extra once inlined. At {@code Threads.MAX} the real call sites track
 * the same 3-4x contention penalty documented in {@code AccumulatorBenchmark} (thread-striped
 * {@code synchronized}, not a CAS retry), without an amplification from real production shapes --
 * {@code onSend}'s three top-level calls plus one grouped {@code update(response, ...)} costs about
 * 4x a single {@code inc()} at both contention levels, exactly what four independent lock
 * acquisitions (three single-counter, one two-counter) should cost, not more. {@code
 * summaryWhileWriting} confirms the peek-not-drain design for {@code summary()}: concurrent readers
 * don't measurably slow writers (0.010 us/op, same as uncontended {@code onCreateSpan}), at the
 * cost of the read itself walking all 54 stripes non-destructively (2.857 us/op) -- acceptable for
 * a diagnostic/tracer-flare call, never on the span-emission path. <code>
 * Apple M1 Max, 10 CPUs - JDK 25 (Zulu) - macOS/aarch64
 * Benchmark                                                Mode  Cnt    Score    Error  Units
 * TracerHealthMetricsBenchmark.onCreateSpan_lowContention   avgt    6    0.009 ±  0.001   us/op
 * TracerHealthMetricsBenchmark.onCreateSpan_highContention  avgt    6    0.032 ±  0.016   us/op
 * TracerHealthMetricsBenchmark.onFailedPublish_lowContention   avgt 6    0.018 ±  0.001   us/op
 * TracerHealthMetricsBenchmark.onFailedPublish_highContention  avgt 6    0.063 ±  0.022   us/op
 * TracerHealthMetricsBenchmark.onPartialPublish_lowContention  avgt 6    0.009 ±  0.001   us/op
 * TracerHealthMetricsBenchmark.onPartialPublish_highContention avgt 6    0.031 ±  0.008   us/op
 * TracerHealthMetricsBenchmark.onSend_lowContention         avgt    6    0.039 ±  0.001   us/op
 * TracerHealthMetricsBenchmark.onSend_highContention        avgt    6    0.097 ±  0.136   us/op
 * TracerHealthMetricsBenchmark.summaryWhileWriting                avgt 6    0.579 ±  0.020  us/op
 * TracerHealthMetricsBenchmark.summaryWhileWriting:...write        avgt 6    0.010 ±  0.001  us/op
 * TracerHealthMetricsBenchmark.summaryWhileWriting:...read         avgt 6    2.857 ±  0.099  us/op
 * </code> ({@code onSend_highContention}'s wide error bar is run-to-run lock-contention noise, the
 * same phenomenon {@code AccumulatorBenchmark} already documents for {@code
 * accumulatorAccumulateAndReset_highContention} -- the direction, not the exact magnitude, is the
 * reliable part of that row.)
 *
 * <p><b>Before/after results:</b> the {@code Accumulator}-backed implementation is measurably
 * slower per counter update than the {@code LongAdder} baseline it replaced -- {@code
 * LongAdder.increment()} is a single uncontended CAS-retry field write, while every {@code
 * Accumulator} update takes its stripe's {@code synchronized} lock even uncontended, so this is the
 * expected shape, not a regression to chase. Uncontended single-counter calls ({@code
 * onCreateSpan}, {@code onPartialPublish}) are within noise of each other (1.1-1.3x); calls
 * touching two counters under one grouped lock ({@code onFailedPublish}, {@code onSend}) cost 2-3x
 * uncontended and 3-4.6x under {@code Threads.MAX} contention, tracking the same 3-4x contention
 * penalty {@code AccumulatorBenchmark} documents for the raw primitive. {@code summary()} is the
 * largest delta: walking 54 stripes non-destructively costs ~4x what summing 52 plain {@code
 * LongAdder} fields did (2.844 vs 0.722 us/op) -- still far below the periodic (30s-default) {@code
 * Flush} cadence and the ad hoc/diagnostic calls that trigger it, so not disqualifying, but the
 * honest number. Neither implementation's writers are measurably slowed by a concurrent {@code
 * summary()}/reader (0.010 vs 0.008 us/op, both within noise). <code>
 * Apple M1 Max, 10 CPUs - JDK 25 (Zulu) - macOS/aarch64
 * Benchmark                       New (Accumulator)  Legacy (LongAdder)  Ratio
 * onCreateSpan_lowContention           0.009               0.007          1.3x
 * onCreateSpan_highContention          0.024               0.009          2.7x
 * onFailedPublish_lowContention        0.018               0.009          2.0x
 * onFailedPublish_highContention       0.074               0.016          4.6x
 * onPartialPublish_lowContention       0.009               0.008          1.1x
 * onPartialPublish_highContention      0.038               0.013          2.9x
 * onSend_lowContention                 0.039               0.013          3.0x
 * onSend_highContention                0.087               0.022          4.0x
 * summaryWhileWriting_write            0.010               0.008          1.3x
 * summaryWhileWriting_read             2.844               0.722          3.9x
 * (all figures us/op, avgt)
 * </code> This is the expected cost of trading 49 independent {@code LongAdder} fields (no shared
 * state, no locking) for one striped-but-shared {@code Accumulator} -- the migration's case rests
 * on eliminating the {@code previousCounts}/{@code countIndex} hand-tracking ceremony and giving
 * each counter an atomic multi-field grouped update, not on raw per-call speed, which is
 * unambiguously a step down here.
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
