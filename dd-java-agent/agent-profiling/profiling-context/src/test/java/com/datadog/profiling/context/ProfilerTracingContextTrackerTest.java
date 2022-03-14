package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfilerTracingContextTrackerTest {
  private final IntervalSequencePruner sequencePruner = new IntervalSequencePruner();
  private ProfilerTracingContextTracker instance;

  @BeforeEach
  void setup() throws Exception {
    instance =
        new ProfilerTracingContextTracker(
            Allocators.heapAllocator(32, 16), null, () -> 100_000L, sequencePruner);
  }

  @Test
  void deactivateContext() {}

  @Test
  void persist() {
    instance =
        new ProfilerTracingContextTracker(
            Allocators.directAllocator(8192, 64), null, () -> 100_000L, sequencePruner);
    for (int i = 0; i < 40; i += 4) {
      instance.activateContext(1L, (i + 1) * 1_000_000L);
      instance.deactivateContext(1L, (i + 2) * 1_000_000L, false);
      instance.activateContext(2L, (i + 3) * 1_000_000L);
      instance.deactivateContext(2L, (i + 4) * 1_000_000L, true);
    }

    byte[] persisted = instance.persist();
    assertNotNull(persisted);

    List<IntervalParser.Interval> intervals = new IntervalParser().parseIntervals(persisted);
    assertEquals(11, intervals.size());

    byte[] encoded = Base64.getEncoder().encode(persisted);
    System.out.println("===> encoded: " + encoded.length);
    System.err.println("===> " + new String(encoded, StandardCharsets.UTF_8));
  }

  @Test
  void testSanity() {
    ProfilerTracingContextTrackerFactory instance =
        new ProfilerTracingContextTrackerFactory(-1, 10L, 512);

    ProfilerTracingContextTracker tracker =
        (ProfilerTracingContextTracker) instance.instance(AgentTracer.NoopAgentSpan.INSTANCE);
    AtomicInteger blobCounter = new AtomicInteger();
    TracingContextTracker.IntervalBlobListener listener =
        (span, blob) -> blobCounter.incrementAndGet();
    tracker.setBlobListeners(Collections.singleton(listener));

    tracker.activateContext();
    tracker.deactivateContext(true);
    tracker.deactivateContext(false);
    byte[] data1 = tracker.persist();
    assertNotNull(data1);
    byte[] data2 = tracker.persist();
    assertNotNull(data2);
    assertArrayEquals(data1, data2);

    assertTrue(tracker.release());
    assertNull(tracker.persist()); // no data after the tracker has been released
    assertFalse(tracker.release()); // double release returns 'false'
    assertNull(tracker.persist()); // no data after the tracker has been double-released
  }
}
