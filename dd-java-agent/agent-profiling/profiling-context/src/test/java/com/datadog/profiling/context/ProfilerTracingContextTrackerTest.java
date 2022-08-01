package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
            Allocators.heapAllocator(32, 16), null, ticker, sequencePruner, 512);
  }

  @Test
  void deactivateContext() {}

  @Test
  void persist() {
    TestTimeTickProvider tickProvider = new TestTimeTickProvider(100_000L, 3, 1_000_000_000L);
    instance =
        new ProfilerTracingContextTracker(
            Allocators.directAllocator(8192, 64),
            null,
            tickProvider,
            sequencePruner,
            Integer.MAX_VALUE);
    for (int i = 0; i < 40; i += 4) {
      instance.activateContext(1L, (i + 1) * 1_000_000L);
      instance.deactivateContext(1L, (i + 2) * 1_000_000L, false);
      instance.activateContext(2L, (i + 3) * 1_000_000L);
      instance.deactivateContext(2L, (i + 4) * 1_000_000L, true);
    }

    assertFalse(instance.isTruncated());
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
  void persistTruncated() {
    int dataSizeLimit = 140;
    TestTimeTickProvider tickProvider = new TestTimeTickProvider(100_000L, 3, 1_000_000_000L);
    instance =
        new ProfilerTracingContextTracker(
            Allocators.directAllocator(8192, 64),
            null,
            tickProvider,
            sequencePruner,
            dataSizeLimit);
    int intervalsCount = 0;
    for (int i = 0; i < 10 * 12; i += 12) {
      instance.activateContext(1L, (i + 1) * 1_000_000L);
      instance.deactivateContext(1L, (i + 2) * 1_000_000L, false);
      instance.activateContext(2L, (i + 3) * 1_000_000L);
      instance.deactivateContext(2L, (i + 4) * 1_000_000L, true);
      instance.activateContext(3L, (i + 5) * 1_000_000L);
      instance.deactivateContext(3L, (i + 6) * 1_000_000L, true);
      instance.activateContext(4L, (i + 7) * 1_000_000L);
      instance.deactivateContext(4L, (i + 8) * 1_000_000L, true);
      instance.activateContext(5L, (i + 9) * 1_000_000L);
      instance.deactivateContext(5L, (i + 10) * 1_000_000L, true);
      instance.activateContext(6L, (i + 11) * 1_000_000L);
      instance.deactivateContext(6L, (i + 11) * 1_000_000L, true);
      instance.activateContext(7L, (i + 12) * 1_000_000L);
      instance.deactivateContext(7L, (i + 12) * 1_000_000L, true);
      intervalsCount += 7;
    }

    assertTrue(instance.isTruncated());
    // set the timestamp after the last transition's timestamp
    tickProvider.set(((11 * 12) * 1_000_000L) + 1);

    byte[] persisted = instance.persist();
    assertNotNull(persisted);

    assertTrue(persisted.length < dataSizeLimit);

    List<IntervalParser.Interval> intervals = new IntervalParser().parseIntervals(persisted);
    // only 8 intervals can be persisted obeying the 128 bytes data limit
    assertTrue(intervals.size() < intervalsCount);

    byte[] encoded = Base64.getEncoder().encode(persisted);
    System.out.println("===> encoded: " + encoded.length);
    System.err.println("===> " + new String(encoded, StandardCharsets.UTF_8));
  }

  @Test
  void testSanity() {
    ProfilerTracingContextTrackerFactory factory =
        new ProfilerTracingContextTrackerFactory(-1, 10L, 512);

    ProfilerTracingContextTracker tracker =
        (ProfilerTracingContextTracker) factory.instance(AgentTracer.NoopAgentSpan.INSTANCE);
    AtomicInteger blobCounter = new AtomicInteger();

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
  void expiringReleaseTest() throws Exception {
    ProfilerTracingContextTrackerFactory factory =
        new ProfilerTracingContextTrackerFactory(
            TimeUnit.HOURS.toNanos(1), TimeUnit.MINUTES.toMillis(5), 512);

    ProfilerTracingContextTracker tracker1 =
        (ProfilerTracingContextTracker) factory.instance(AgentTracer.NoopAgentSpan.INSTANCE);
    ProfilerTracingContextTracker tracker2 =
        (ProfilerTracingContextTracker) factory.instance(AgentTracer.NoopAgentSpan.INSTANCE);

    WeakReference<ProfilerTracingContextTracker> tracker1Ref = new WeakReference<>(tracker1);
    WeakReference<ProfilerTracingContextTracker> tracker2Ref = new WeakReference<>(tracker2);

    ExpirationTracker.Expirable expirable1 = tracker1.getExpirable();
    ExpirationTracker.Expirable expirable2 = tracker2.getExpirable();

    // make sure the tracker release will also expire the expiring instance
    tracker1.release();
    assertTrue(expirable1.isExpired());
    assertTrue(tracker1.isReleased());
    // after release the back-reference to the context tracker must be gone
    tracker1 = null;
    forceGc(30, TimeUnit.SECONDS);
    assertNull(tracker1Ref.get());

    // make sure that the expiration will also trigger the tracker release
    expirable2.expire();
    assertTrue(expirable2.isExpired());
    assertTrue(tracker2.isReleased());
    // after release the back-reference to the context tracker must be gone
    tracker2 = null;
    forceGc(30, TimeUnit.SECONDS);
    assertNull(tracker2Ref.get());
  }

  /**
   * This method will try to force GC cycle within the given timeout.
   *
   * @param timeout the timeout value
   * @param timeUnit the timeout unit
   * @throws TimeoutException if invoking explicit GC didn't trigger GC cycle within the timeout
   */
  private static void forceGc(long timeout, TimeUnit timeUnit) throws TimeoutException {
    long timeoutNs = timeUnit.toNanos(timeout);
    long startTs = System.nanoTime();
    List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
    long counts = beans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    do {
      if (System.nanoTime() - startTs > timeoutNs) {
        throw new TimeoutException();
      }
      System.gc();
    } while (counts == beans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum());
  }

  @Test
  @Disabled
  // a handy test to try deserializing data from spans
  void testPersisted() {
    String[] encodedData =
        new String[] {
          "AAAAI6SK7u6LAcG57+//L7gXB4IDGEYITQFPAVEBUwHgAQEAAACvCi3WjrSyLhomSh0EAQl0Lpo45iXmARamNA4qQB9qOIQw/iiKJQ5shA7WC/4dMh2mO2wXaBfGKVAXWDwQFF4eSismAQeqAjJMMl4kIg3wGSxaUBRERkjb8CSsJC48VCJqFuAMolksb/TEAVe8JXuksSoCrFqEErM8BmcapgmfBDbYWgAf/ix8C4IM7AkKFbyEhigRaODq3X69tgLxcRxQLgMaybJTKgLqZGg+ZJKJKJJJJJJJJJJKRJJJJJJJSRaaJJJJSRZZKAAAAA"
        };
    for (String encoded : encodedData) {
      System.out.println("====");
      byte[] decoded = Base64.getDecoder().decode(encoded);

      for (IntervalParser.Interval i : new IntervalParser().parseIntervals(decoded)) {
        System.out.println(
            "===> "
                + Instant.ofEpochMilli(i.from / 1000000)
                + " - "
                + Instant.ofEpochMilli(i.till / 1000000)
                + "  :: "
                + (i.till - i.from));
      }
    }
  }
}
