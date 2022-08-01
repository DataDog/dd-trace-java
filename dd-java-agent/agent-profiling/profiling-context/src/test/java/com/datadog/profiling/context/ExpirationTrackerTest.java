package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpirationTrackerTest {
  private static final long expiration = 5;
  private static final long granularity = 1;
  private static final int capacity = 20;

  private static final TimeUnit timeUnit = TimeUnit.MILLISECONDS;
  private ExpirationTracker instance;

  private final ManualNanoTimeSource timeSource = new ManualNanoTimeSource();

  @BeforeEach
  void setup() throws Exception {
    instance =
        new ExpirationTracker(expiration, granularity, timeUnit, 2, capacity, timeSource, false);
  }

  @AfterEach
  void shutdown() throws Exception {
    instance.close();
  }

  @Test
  void checkCapacityWithExpiration() throws Exception {
    // the elements are regularly expired 5 ticks in the past
    // this should give enough room to continue adding elements past the asked capacity
    AtomicInteger expiredCnt = new AtomicInteger();
    int elements = 2 * instance.capacity();
    for (int i = 0; i < elements; i++) {
      ExpirationTracker.Expirable e = instance.track(expiredCnt::incrementAndGet);
      assertNotNull(e);
      assertNotEquals(ExpirationTracker.Expirable.EMPTY, e, "element " + i);

      timeSource.proceed(1, timeUnit);
      instance.processCleanup();
    }

    assertEquals(elements - expiration, expiredCnt.get());
  }

  @Test
  void checkCapacitySingleBucket() throws Exception {
    for (int i = 0; i < instance.bucketCapacity(); i++) {
      ExpirationTracker.Expirable e = instance.track(() -> {});
      assertNotNull(e);
      assertNotEquals(ExpirationTracker.Expirable.EMPTY, e, "Element " + i);
    }
    ExpirationTracker.Expirable e = instance.track(() -> {});
    assertNotNull(e);

    // capacity is exhausted - the tracking should be refused
    assertEquals(ExpirationTracker.Expirable.EMPTY, e);
  }

  @Test
  void checkBrokenCleanup() {
    // here we are not going to move forward the cleaner ticks so we should start geting NOOPs after
    // the 'expiration' number of adds
    // this test depends on the internal implementation of MpscArrayQueue which rounds up
    // the asked capacity (10) to the nearest larger power of two (16)
    for (int i = 0; i < 16; i++) {
      ExpirationTracker.Expirable e = instance.track(() -> {});
      assertNotNull(e);
      assertNotEquals(ExpirationTracker.Expirable.EMPTY, e);
      timeSource.proceed(1, timeUnit);
    }

    // time source must be moved forward asynchronously -
    // otherwise the attempt to cleanup will never time out
    new Timer(true)
        .schedule(
            new TimerTask() {
              @Override
              public void run() {
                timeSource.proceed(510, TimeUnit.MILLISECONDS);
              }
            },
            500L);
    ExpirationTracker.Expirable e = instance.track(() -> {});
    assertEquals(ExpirationTracker.Expirable.EMPTY, e);
  }

  @Test
  void checkExpireForce() {
    long ts = 0;
    AtomicBoolean expired = new AtomicBoolean();
    ExpirationTracker.Expirable e = instance.track(() -> expired.set(true));

    instance.processCleanup();
    assertFalse(expired.get());
    assertFalse(e.isExpired());
    assertTrue(e.isExpiring(timeUnit.toNanos(ts + expiration)));

    e.expire();
    assertTrue(expired.get());
    assertTrue(e.isExpired());
    assertFalse(e.isExpiring(timeUnit.toNanos(ts + expiration)));
  }

  @Test
  void checkBucketFillup() {
    AtomicBoolean isFull = new AtomicBoolean(false);
    ExpirationTracker.Bucket.Callback callback =
        new ExpirationTracker.Bucket.Callback() {
          @Override
          public void onBucketFull() {
            isFull.set(true);
          }

          @Override
          public void onBucketAvailable() {
            isFull.set(false);
          }
        };

    ExpirationTracker.Bucket bucket = new ExpirationTracker.Bucket(1, callback);

    assertFalse(isFull.get());
    ExpirationTracker.Expirable e = bucket.add(0, TimeUnit.MILLISECONDS.toNanos(1), b -> {});
    assertTrue(isFull.get());
    ExpirationTracker.Expirable e1 = bucket.add(0, TimeUnit.MILLISECONDS.toNanos(1), b -> {});
    assertEquals(ExpirationTracker.Expirable.EMPTY, e1);

    e.expire();
    assertFalse(isFull.get());
    e1 = bucket.add(0, timeUnit.toNanos(1), b -> {});
    assertNotEquals(ExpirationTracker.Expirable.EMPTY, e1);
  }
}
