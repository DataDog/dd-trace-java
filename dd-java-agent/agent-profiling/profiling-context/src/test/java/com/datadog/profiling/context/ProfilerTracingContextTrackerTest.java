package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class ProfilerTracingContextTrackerTest {
  private static final class TestTimeTickProvider
      implements ProfilerTracingContextTracker.TimeTicksProvider {
    private final long step;
    private final long frequency;
    private final AtomicLong ts = new AtomicLong(0);

    public TestTimeTickProvider(long initial, long step, long frequency) {
      this.ts.set(initial);
      this.step = step;
      this.frequency = frequency;
    }

    @Override
    public long ticks() {
      return ts.getAndAdd(step);
    }

    @Override
    public long frequency() {
      return frequency;
    }

    public void set(long ticks) {
      ts.set(ticks);
    }
  }

  private final IntervalSequencePruner sequencePruner = new IntervalSequencePruner();
  private ProfilerTracingContextTracker instance;

  @BeforeEach
  void setup() throws Exception {
    ProfilerTracingContextTracker.TimeTicksProvider ticker =
        new TestTimeTickProvider(100_000L, 1000L, 1_000_000_000L);
    instance =
        new ProfilerTracingContextTracker(
            Allocators.heapAllocator(32, 16), null, ticker, sequencePruner);
  }

  @Test
  void deactivateContext() {}

  @Test
  void persist() {
    TestTimeTickProvider tickProvider = new TestTimeTickProvider(100_000L, 3, 1_000_000_000L);
    instance =
        new ProfilerTracingContextTracker(
            Allocators.directAllocator(8192, 64), null, tickProvider, sequencePruner);
    for (int i = 0; i < 40; i += 4) {
      instance.activateContext(1L, (i + 1) * 1_000_000L);
      instance.deactivateContext(1L, (i + 2) * 1_000_000L, false);
      instance.activateContext(2L, (i + 3) * 1_000_000L);
      instance.deactivateContext(2L, (i + 4) * 1_000_000L, true);
    }
    // set the timestamp after the last transition's timestamp
    tickProvider.set((44 * 1_000_000L) + 1);

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
    tracker.maybeDeactivateContext();
    tracker.deactivateContext();
    byte[] data1 = tracker.persist();
    assertNotNull(data1);
    byte[] data2 = tracker.persist();
    assertNotNull(data2);
    assertArrayEquals(data1, data2);

    assertTrue(tracker.release());
    assertArrayEquals(data1, tracker.persist()); // previously persisted data survives release
    assertFalse(tracker.release()); // double release returns 'false'
    assertArrayEquals(
        data1, tracker.persist()); // previously persisted data survives double-release
  }

  @Test
  void delayedCompareToTest() {
    ProfilerTracingContextTrackerFactory instance =
        new ProfilerTracingContextTrackerFactory(-1, 10L, 512);

    ProfilerTracingContextTracker tracker1 =
        (ProfilerTracingContextTracker) instance.instance(AgentTracer.NoopAgentSpan.INSTANCE);
    ProfilerTracingContextTracker tracker2 =
        (ProfilerTracingContextTracker) instance.instance(AgentTracer.NoopAgentSpan.INSTANCE);

    TracingContextTracker.DelayedTracker delayed1 = tracker1.asDelayed();
    TracingContextTracker.DelayedTracker delayed2 = tracker2.asDelayed();

    Delayed other =
        new Delayed() {
          @Override
          public long getDelay(TimeUnit unit) {
            return 0;
          }

          @Override
          public int compareTo(Delayed o) {
            return 0;
          }
        };

    assertEquals(0, delayed1.compareTo(other));
    assertEquals(0, delayed1.compareTo(delayed1));

    tracker2.activateContext();
    assertEquals(-1, delayed1.compareTo(delayed2));
    assertEquals(1, delayed2.compareTo(delayed1));
  }

  @Test
  void delayedCachingTest() {
    ProfilerTracingContextTrackerFactory instance =
        new ProfilerTracingContextTrackerFactory(-1, 10L, 512);

    ProfilerTracingContextTracker tracker1 =
        (ProfilerTracingContextTracker) instance.instance(AgentTracer.NoopAgentSpan.INSTANCE);
    ProfilerTracingContextTracker tracker2 =
        (ProfilerTracingContextTracker) instance.instance(AgentTracer.NoopAgentSpan.INSTANCE);

    TracingContextTracker.DelayedTracker delayed1 = tracker1.asDelayed();
    TracingContextTracker.DelayedTracker delayed2 = tracker2.asDelayed();

    assertEquals(delayed1, tracker1.asDelayed());
    assertEquals(delayed2, tracker2.asDelayed());
  }

  @Test
  void delayedReleaseTest() {
    ProfilerTracingContextTrackerFactory instance =
        new ProfilerTracingContextTrackerFactory(-1, 10L, 512);

    ProfilerTracingContextTracker tracker1 =
        (ProfilerTracingContextTracker) instance.instance(AgentTracer.NoopAgentSpan.INSTANCE);
    ProfilerTracingContextTracker tracker2 =
        (ProfilerTracingContextTracker) instance.instance(AgentTracer.NoopAgentSpan.INSTANCE);

    TracingContextTracker.DelayedTracker delayed1 = tracker1.asDelayed();
    TracingContextTracker.DelayedTracker delayed2 = tracker2.asDelayed();

    // make sure the tracker release will remove the reference from delayed to the tracker
    tracker1.release();
    assertNull(((ProfilerTracingContextTracker.DelayedTrackerImpl) delayed1).ref);
    assertNotEquals(delayed1, tracker1.asDelayed());

    // make sure that the delayed cleanup will also trigger the tracker release
    delayed2.cleanup();
    assertNull(((ProfilerTracingContextTracker.DelayedTrackerImpl) delayed2).ref);
    assertNotEquals(delayed2, tracker2.asDelayed());
  }

  @Test
  @Disabled
  // a handy test to try deserializing data from spans
  void testPersisted() {
    String[] encodedData =
        new String[] {
          "AAAAIvb1iIiHwkvoBwiTAgGUAgGWAgFGDUcCVQFlAe4CGwAAANIs2DlgHjBMYJEXLl8F9uUrUcucNQGInnTTAwndBz7DCpqhagMQAeo+AXvDNIieDGCUFu4C3RN1CSwNvwUxW68NbxvvAasDdAGCCUgp69kBCK0BIJqNuS9/k15V1gHKg5YBxJlMOpIJQwPKAb2MrAMLHBsBslYoAjQBoAPEDtYC6AFkAUwb3QI6AXgBgAovAv4DQQF7GUID1miBAl4zJAlBDeICCAkWBgYRkw1LTvUfHgP6AaoBcQGSGLkBPwQvAlQCIwGwBUgBuwRwAS4IzUUUUUUiSmUSSSSSSUkUQ2SSSSSSSSSSSSSSSSSSSSSSSSSSQAA"
        };
    for (String encoded : encodedData) {
      System.out.println("====");
      byte[] decoded = Base64.getDecoder().decode(encoded);

      for (IntervalParser.Interval i : new IntervalParser().parseIntervals(decoded)) {
        System.out.println("===> " + i.from + ":" + i.till + "  - " + (i.till - i.from));
      }
    }
  }
}
