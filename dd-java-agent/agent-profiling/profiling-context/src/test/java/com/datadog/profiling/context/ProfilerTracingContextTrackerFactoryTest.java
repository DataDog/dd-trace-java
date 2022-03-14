package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.profiling.TracingContextTracker;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProfilerTracingContextTrackerFactoryTest {
  @Test
  void testReleasedAfterInactivity() throws Exception {
    long inactivityMs = 100;
    ProfilerTracingContextTrackerFactory instance =
        new ProfilerTracingContextTrackerFactory(
            TimeUnit.NANOSECONDS.convert(inactivityMs, TimeUnit.MILLISECONDS), 10L);
    TracingContextTracker tracker = instance.instance(null);

    Thread.sleep((long) (inactivityMs * 1.5d));
    assertFalse(
        tracker.release(), "Tracker resources were not released within " + inactivityMs + "ms");
  }

  @Test
  void testNotReleasedBeforeInactivity() throws Exception {
    long inactivityMs = 100;
    ProfilerTracingContextTrackerFactory instance =
        new ProfilerTracingContextTrackerFactory(
            TimeUnit.NANOSECONDS.convert(inactivityMs, TimeUnit.MILLISECONDS), 10L);
    TracingContextTracker tracker = instance.instance(null);

    Thread.sleep((long) (inactivityMs * 0.5d));
    assertTrue(
        tracker.release(),
        "Tracker resources were erroneously released before " + inactivityMs + "ms");
  }

  @Test
  void testNoInactivityRelease() throws Exception {
    long inactivityMs = 0;
    ProfilerTracingContextTrackerFactory instance =
        new ProfilerTracingContextTrackerFactory(
            TimeUnit.NANOSECONDS.convert(inactivityMs, TimeUnit.MILLISECONDS), 10L);
    TracingContextTracker tracker = instance.instance(null);

    Thread.sleep((long) (inactivityMs * 1.5d));
    assertTrue(tracker.release(), "Tracker resources are erroneously released");
  }
}
