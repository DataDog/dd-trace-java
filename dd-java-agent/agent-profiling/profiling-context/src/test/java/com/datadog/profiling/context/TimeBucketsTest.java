package com.datadog.profiling.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TimeBucketsTest {
  private static long expiration = 5;
  private static long granularity = 1;
  private static int capacity = 20;

  private TimeBuckets instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new TimeBuckets(expiration, granularity, TimeUnit.MILLISECONDS, 2, capacity, false);
  }

  @AfterEach
  void shutdown() throws Exception {
    instance.close();
  }

  @Test
  void checkCapacity() throws Exception {
    for (int i = 0; i < capacity; i++) {
      TimeBuckets.Expiring e = instance.add(i, () -> {});
      assertNotNull(e);
      assertNotEquals(TimeBuckets.Expiring.NOOP, e);

      // cleaner thread 'tick'
      instance.processCleanup(i);
    }
  }

  @Test
  void checkBrokenCleanup() {
    // here we are not going to move forward the cleaner ticks so we should start geting NOOPs after the 'expiration' number of adds

    for (int i = 0; i < 16; i++) {
      TimeBuckets.Expiring e = instance.add(i, () -> {});
      assertNotNull(e);
      assertNotEquals(TimeBuckets.Expiring.NOOP, e);
    }

    TimeBuckets.Expiring e = instance.add(17, () -> {});
    assertEquals(TimeBuckets.Expiring.NOOP, e);
  }

  @Test
  void test() throws Exception {
    int limit = 4;
    int iterations = limit * 10;
    int backIdx = limit / 2;
        TimeBuckets.Expiring[] expirings = new TimeBuckets.Expiring[limit];
    boolean check[] = new boolean[iterations - limit];

    for (int i = 0; i < iterations; i++) {
      if (i < (iterations - limit)) {
        int target = i;
        expirings[i % expirings.length] = instance.add(i, () -> check[target] = true);
      } else {
        instance.expireBucket(i);
      }

      if (i > backIdx) {
        expirings[(i - backIdx) % expirings.length].touch(i);
      }
      instance.processCleanup(i);
    }
    instance.processCleanup(iterations);
    System.out.println(Arrays.toString(check));
  }

}
