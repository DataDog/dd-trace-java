package datadog.trace.api.telemetry;

import static datadog.trace.api.telemetry.MetricCollector.RAW_QUEUE_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.metrics.SpanMetricRegistryImpl;
import datadog.trace.api.telemetry.CoreMetricCollector.CoreMetric;
import java.util.Collection;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link CoreMetricCollector#prepareMetrics()} never drops a counter's delta when the
 * output queue is full.
 *
 * <p>The drain checks {@code remainingCapacity()} before reading each counter and stops early once
 * the queue can't take any more, leaving the untouched counters' deltas intact for the next cycle.
 * Reading a counter with {@code getValueAndReset()} clears its delta baseline, so that check has to
 * happen before the read, not after a failed {@code offer()}.
 */
class CoreMetricCollectorQueueFullTest {

  /**
   * Fills the drain past its queue capacity and verifies that every counter's delta is reported
   * exactly once across as many cycles as it takes to empty — none dropped while the queue is full.
   *
   * <p>{@link CoreMetricCollector} is a singleton over the global {@link SpanMetricRegistryImpl},
   * so this test can't assume a clean registry. It stays robust by tagging its own instrumentations
   * with a unique prefix and counting only those, and it drains cycle-by-cycle until the queue
   * yields nothing at all (so it never terminates early just because a cycle happened to surface
   * only unrelated counters left behind by other tests). The sum of our own metrics across all
   * cycles must equal the number we created; a dropped delta would make the sum come up short.
   */
  @Test
  void skippedCountersRetainDeltaAcrossCycles() {
    SpanMetricRegistryImpl registry = SpanMetricRegistryImpl.getInstance();
    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    // Discard anything left in the queue by other tests sharing the singleton.
    collector.drain();

    // Unique instrumentation-name prefix so we count only the instrumentations this test creates,
    // regardless of whatever else has already polluted the shared registry. CoreMetricCollector
    // emits these as "integration_name:<name>" tags.
    String namePrefix = "queue-full-test-" + System.nanoTime() + "-";
    String tagPrefix = "integration_name:" + namePrefix;

    // Create more non-zero counters than the queue can hold so at least one collection cycle
    // overflows. A single onSpanCreated() per instrumentation leaves exactly one non-zero counter
    // (spans_created) each; the rest read as 0 and are skipped.
    int created = RAW_QUEUE_SIZE + 200;
    for (int i = 0; i < created; i++) {
      registry.get(namePrefix + i).onSpanCreated();
    }

    // Drain cycle-by-cycle until the queue is fully emptied, summing how many of our own metrics
    // surface. Guard the loop so a bug that never converges fails loudly instead of hanging.
    int seen = 0;
    int cycles = 0;
    while (true) {
      collector.prepareMetrics();
      Collection<CoreMetric> drained = collector.drain();
      if (drained.isEmpty()) {
        break;
      }
      for (CoreMetric metric : drained) {
        if (isOurs(metric, tagPrefix)) {
          seen++;
        }
      }
      assertTrue(++cycles < 20, "drain did not converge; possible lost/looping delta");
    }

    assertEquals(
        created,
        seen,
        "every counter's delta must be reported exactly once; a full queue must not drop any");
  }

  private static boolean isOurs(CoreMetric metric, String tagPrefix) {
    for (String tag : metric.tags) {
      if (tag.startsWith(tagPrefix)) {
        return true;
      }
    }
    return false;
  }
}
