package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.api.profiling.TracingContextTrackerFactory;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PerSpanTracingContextTrackerFactoryTest {
  @Test
  void testTracingAvailable() {
    assertFalse(TracingContextTrackerFactory.isTrackingAvailable());

    TracingContextTrackerFactory.registerImplementation(
        new PerSpanTracingContextTrackerFactory(
            TimeUnit.NANOSECONDS.convert(100, TimeUnit.MILLISECONDS), 10L, 512));
    assertTrue(TracingContextTrackerFactory.isTrackingAvailable());
  }

  @Test
  void testReleasedAfterInactivity() throws Exception {
    long inactivityMs = 100;
    PerSpanTracingContextTrackerFactory instance =
        new PerSpanTracingContextTrackerFactory(
            TimeUnit.NANOSECONDS.convert(inactivityMs, TimeUnit.MILLISECONDS), 10L, 512);
    TracingContextTracker tracker1 = instance.instance(null);
    TracingContextTracker tracker2 = instance.instance(null);

    Thread.sleep((long) (inactivityMs * 1.5d));
    assertFalse(
        tracker1.release(), "Tracker resources were not released within " + inactivityMs + "ms");
    assertFalse(
        tracker2.release(), "Tracker resources were not released within " + inactivityMs + "ms");
  }

  @Test
  void testNotReleasedBeforeInactivity() throws Exception {
    long inactivityMs = 100;
    PerSpanTracingContextTrackerFactory instance =
        new PerSpanTracingContextTrackerFactory(
            TimeUnit.NANOSECONDS.convert(inactivityMs, TimeUnit.MILLISECONDS), 10L, 512);
    TracingContextTracker tracker1 = instance.instance(null);
    TracingContextTracker tracker2 = instance.instance(null);

    Thread.sleep((long) (inactivityMs * 0.5d));
    assertTrue(
        tracker1.release(),
        "Tracker resources were erroneously released before " + inactivityMs + "ms");
    assertTrue(
        tracker2.release(),
        "Tracker resources were erroneously released before " + inactivityMs + "ms");
  }

  @Test
  void testNoInactivityRelease() throws Exception {
    long inactivityMs = 0;
    PerSpanTracingContextTrackerFactory instance =
        new PerSpanTracingContextTrackerFactory(
            TimeUnit.NANOSECONDS.convert(inactivityMs, TimeUnit.MILLISECONDS), 10L, 512);
    TracingContextTracker tracker1 = instance.instance(null);
    TracingContextTracker tracker2 = instance.instance(null);

    Thread.sleep((long) (inactivityMs * 1.5d));
    assertTrue(tracker1.release(), "Tracker resources are erroneously released");
    assertTrue(tracker2.release(), "Tracker resources are erroneously released");
  }
}
