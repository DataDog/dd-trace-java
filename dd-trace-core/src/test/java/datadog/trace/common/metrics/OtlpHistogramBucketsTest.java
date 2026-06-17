package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.OtlpHistogramBuckets.BOUNDS_SECONDS;
import static datadog.trace.common.metrics.OtlpHistogramBuckets.EXPLICIT_BOUNDS;
import static datadog.trace.common.metrics.OtlpHistogramBuckets.bucketIndex;
import static datadog.trace.common.metrics.OtlpHistogramBuckets.toHistogramPoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.api.Histogram;
import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link OtlpHistogramBuckets}, the helper that re-bins a nanosecond-valued DDSketch
 * onto the fixed OTLP explicit-bounds histogram layout (in seconds).
 *
 * <p>Both methods under test ({@code bucketIndex}, {@code toHistogramPoint}) are package-private,
 * so this test lives in {@code datadog.trace.common.metrics}.
 */
class OtlpHistogramBucketsTest {

  private static final double NANOS_PER_SECOND = 1_000_000_000d;

  // Tolerance for double assertions on exactly-computed values (e.g. sum from sumNanos, integer
  // counts). DDSketch-derived values like min/max use looser tolerances inline, since the sketch
  // only preserves values to within its relative accuracy.
  private static final double EPS = 1e-9;

  @BeforeAll
  static void registerHistogramFactory() {
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  // ── bucketIndex ──────────────────────────────────────────────────────────

  static Stream<Arguments> boundsWithIndex() {
    return IntStream.range(0, BOUNDS_SECONDS.length)
        .mapToObj(i -> Arguments.of(i, BOUNDS_SECONDS[i]));
  }

  @ParameterizedTest(name = "value exactly on BOUNDS_SECONDS[{0}]={1} returns index {0}")
  @MethodSource("boundsWithIndex")
  void valueExactlyOnBoundReturnsThatIndex(int index, double bound) {
    // <= semantics: a value exactly on a bound falls in that bound's bucket.
    assertEquals(index, bucketIndex(bound));
  }

  @Test
  void valueJustAboveSmallBoundFallsInNextBucket() {
    // 0.002 is the first bound; a value just above it but below the second bound (0.004)
    // must roll into the next index.
    assertEquals(0, bucketIndex(0.002));
    assertEquals(1, bucketIndex(0.0020001));
    assertEquals(1, bucketIndex(0.004));
  }

  @Test
  void valueAboveLargestBoundIsOverflow() {
    // > 15s overflows to the final (16th) index.
    assertEquals(BOUNDS_SECONDS.length, bucketIndex(15.0000001));
    assertEquals(BOUNDS_SECONDS.length, bucketIndex(100.0));
    // exactly 15 (the largest non-overflow bound) is the last non-overflow index.
    assertEquals(BOUNDS_SECONDS.length - 1, bucketIndex(15.0));
  }

  // ── EXPLICIT_BOUNDS layout ────────────────────────────────────────────────

  @Test
  void explicitBoundsHas17EntriesEndingInInfinity() {
    assertEquals(17, EXPLICIT_BOUNDS.size());
    assertEquals(Double.POSITIVE_INFINITY, EXPLICIT_BOUNDS.get(EXPLICIT_BOUNDS.size() - 1));
  }

  // ── toHistogramPoint ──────────────────────────────────────────────────────

  @Test
  void toHistogramPointSummary() {
    Histogram h = Histogram.newHistogram();
    // Samples spanning the layout, including a 1ns duration — the upstream-clamped minimum
    // (DDSpan#finishAndAddToTrace does Math.max(1, durationNano)). 1ns is far below the smallest
    // bound (0.002s) and must land in bucket 0, not be dropped. 1ms also falls in bucket 0, so the
    // two sub-2ms samples share it; 100ms → bucket 6, 3s → bucket 13.
    long[] samplesNanos = {
      1L,
      (long) (0.001 * NANOS_PER_SECOND),
      (long) (0.1 * NANOS_PER_SECOND),
      (long) (3.0 * NANOS_PER_SECOND)
    };
    for (long s : samplesNanos) {
      h.accept(s);
    }

    // Use a sumNanos that deliberately differs from the sketch's implied sum to prove the
    // returned sum comes from the ARGUMENT, not the sketch.
    long sumNanos = 42L * (long) NANOS_PER_SECOND;
    OtlpHistogramPoint point = toHistogramPoint(h, sumNanos);

    // 17 bucket counts (the EXPLICIT_BOUNDS layout itself is covered by its own test).
    assertEquals(17, point.bucketCounts.size());

    // total count == number of samples.
    assertEquals(samplesNanos.length, (long) point.count);

    // max is the 3s sample (CollapsingLowestDenseStore collapses the LOWEST bins, so the top is
    // preserved accurately). The exact 1ns min is NOT recoverable: over this wide value range the
    // lowest bins collapse, so getMinValue reports the collapsed bin (sub-2ms here), not 1ns. The
    // tiny sample isn't lost though — that's proven by the count and bucket-0 assertions below.
    // DDSketch is relative-accuracy, so min/max use loose tolerances.
    assertTrue(
        point.min > 0 && point.min <= BOUNDS_SECONDS[0], "min collapses into bucket 0 range");
    assertEquals(3.0, point.max, 1e-2);

    // sum equals the sumNanos ARGUMENT converted to seconds, not the sketch sum.
    assertEquals(42.0, point.sum, EPS);

    // The two sub-2ms samples (1ns + 1ms) both land in bucket 0; nothing is lost: bucket counts
    // sum to the total count. The count==Σbuckets invariant guards against a future histogram
    // config that parks tiny values in a zero/negative store (excluded from getBinCounts).
    long bucketTotal = 0;
    for (int i = 0; i < point.bucketCounts.size(); i++) {
      long c = (long) point.bucketCounts.get(i).doubleValue();
      bucketTotal += c;
      if (i == 0) {
        assertEquals(2L, c, "1ns + 1ms both land in bucket 0 (<= 0.002s)");
      }
    }
    assertEquals(samplesNanos.length, bucketTotal);
  }

  @Test
  void emptyHistogramHasZeroMinMaxAndCount() {
    Histogram h = Histogram.newHistogram();
    OtlpHistogramPoint point = toHistogramPoint(h, 0L);

    assertEquals(0.0, point.count, EPS);
    assertEquals(0.0, point.min, EPS);
    assertEquals(0.0, point.max, EPS);
    long bucketTotal = 0;
    for (double c : point.bucketCounts) {
      bucketTotal += (long) c;
    }
    assertEquals(0L, bucketTotal);
  }
}
