package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntervalCollapsingEmitterTest {
  private static final long THRESHOLD = 10L;
  private List<long[]> intervals;
  private IntervalCollapser instance;

  @BeforeEach
  void setUp() {
    intervals = new ArrayList<>();
    instance = new IntervalCollapser(THRESHOLD, 0, (s, d) -> intervals.add(new long[] {s, d}));
  }

  @Test
  void processDisjointIntervals() {
    long interval1Start = instance.processDelta(0); // start
    long interval1End = instance.processDelta(10); // end
    long interval2Start =
        instance.processDelta(
            THRESHOLD * 2); // start after 2*THRESHOLD -> will be a disjoint interval
    long interval2End = instance.processDelta(20); // end
    instance.finish();

    assertEquals(2, intervals.size());
    assertArrayEquals(new long[] {interval1Start, interval1End - interval1Start}, intervals.get(0));
    assertArrayEquals(new long[] {interval2Start, interval2End - interval2Start}, intervals.get(1));
  }

  @Test
  void processCollapsedIntervals1() {
    long startTimestamp = instance.processDelta(0); // start
    instance.processDelta(10); // end
    instance.processDelta(THRESHOLD / 2); // start after THRESHOLD / 2 -> collapse with previous
    long finalTimestamp = instance.processDelta(20); // end
    instance.finish();

    assertEquals(1, intervals.size());
    assertArrayEquals(
        new long[] {startTimestamp, finalTimestamp - startTimestamp}, intervals.get(0));
  }

  @Test
  void processCollapsedIntervals2() {
    long startInterval1 = instance.processDelta(0); // start
    instance.processDelta(10); // end
    instance.processDelta(THRESHOLD / 2); // start after THRESHOLD / 2 -> collapse with previous
    long endInterval1 = instance.processDelta(20); // end
    long startInterval2 =
        instance.processDelta(THRESHOLD * 2); // start after THRESHOLD * 2 -> disjoint interval
    long endInterval2 = instance.processDelta(10);
    instance.finish();

    assertEquals(2, intervals.size());
    assertArrayEquals(new long[] {startInterval1, endInterval1 - startInterval1}, intervals.get(0));
    assertArrayEquals(new long[] {startInterval2, endInterval2 - startInterval2}, intervals.get(1));
  }
}
