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
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 3, time = 10)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(2)
public class TracerHealthMetricsBenchmark {

  private final TracerHealthMetrics metrics = new TracerHealthMetrics(StatsDClient.NO_OP);
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
}
