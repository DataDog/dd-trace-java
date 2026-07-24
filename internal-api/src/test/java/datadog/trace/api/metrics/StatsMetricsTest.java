package datadog.trace.api.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StatsMetrics}. The instance is a process-wide singleton, so each test uses
 * its own uniquely-named collapse reasons to stay isolated from other tests' counters.
 */
class StatsMetricsTest {

  private static Map<String, StatsMetrics.TaggedCounter> countersByTag() {
    return StatsMetrics.getInstance().getTaggedCounters().stream()
        .collect(Collectors.toMap(StatsMetrics.TaggedCounter::getTag, Function.identity()));
  }

  @Test
  void accumulatesPerReasonAndEmitsResetDeltas() {
    StatsMetrics metrics = StatsMetrics.getInstance();
    String reason = "collapsed:test_accumulate";

    metrics.onCollapsedSpans(reason, 3);
    metrics.onCollapsedSpans(reason, 4);

    StatsMetrics.TaggedCounter counter = countersByTag().get(reason);
    assertEquals(StatsMetrics.COLLAPSED_SPANS, counter.getName());
    assertEquals(reason, counter.getTag());
    assertEquals(7, counter.getValue(), "getValue reports the running total");

    // First drain returns the whole accumulated delta; a second drain with no activity returns 0.
    assertEquals(7, counter.getValueAndReset(), "first drain returns the accumulated delta");
    assertEquals(0, counter.getValueAndReset(), "no new activity -> zero delta");

    metrics.onCollapsedSpans(reason, 5);
    assertEquals(5, counter.getValueAndReset(), "only the post-drain increment is returned");
  }

  @Test
  void separateReasonsGetSeparateCounters() {
    StatsMetrics metrics = StatsMetrics.getInstance();
    String collapsed = "collapsed:test_separate";
    String oversized = "oversized:test_separate";

    metrics.onCollapsedSpans(collapsed, 2);
    metrics.onCollapsedSpans(oversized, 9);

    Map<String, StatsMetrics.TaggedCounter> counters = countersByTag();
    assertEquals(2, counters.get(collapsed).getValue());
    assertEquals(9, counters.get(oversized).getValue());
  }

  @Test
  void nonPositiveCountsAreIgnored() {
    StatsMetrics metrics = StatsMetrics.getInstance();
    String reason = "collapsed:test_nonpositive";

    metrics.onCollapsedSpans(reason, 0);
    metrics.onCollapsedSpans(reason, -5);

    // No counter is created for a reason that never saw a positive count.
    assertNull(countersByTag().get(reason), "no counter created for non-positive counts");

    metrics.onCollapsedSpans(reason, 4);
    assertEquals(4, countersByTag().get(reason).getValue());
    // A later non-positive count leaves the running total untouched.
    metrics.onCollapsedSpans(reason, -1);
    assertEquals(4, countersByTag().get(reason).getValue());
  }

  @Test
  void reasonCounterIsStableAcrossLookups() {
    StatsMetrics metrics = StatsMetrics.getInstance();
    String reason = "collapsed:test_stable";

    metrics.onCollapsedSpans(reason, 1);
    StatsMetrics.TaggedCounter first = countersByTag().get(reason);
    metrics.onCollapsedSpans(reason, 1);
    StatsMetrics.TaggedCounter second = countersByTag().get(reason);

    assertTrue(first == second, "same reason maps to the same counter instance");
    assertEquals(2, first.getValue());
  }
}
