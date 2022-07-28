package com.datadog.profiling.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TimeBucketsTest {
  private TimeBuckets instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new TimeBuckets(5, 1, TimeUnit.MILLISECONDS, 2, 20);
  }

  @AfterEach
  void shutdown() throws Exception {
    instance.close();
  }

  @Test
  void test() throws Exception {
    TimeBuckets.Expiring[] expirings = new TimeBuckets.Expiring[10];
    boolean check[] = new boolean[expirings.length + 4];

    for (int i = 0; i < check.length + 4; i++) {
      if (i < 10) {
        int target = i;
        expirings[i % expirings.length] = instance.add(i, () -> check[target] = true);
      }
      if (i > 5) {
        expirings[(i - 5) % expirings.length].touch(i);
        instance.expireBucket(i - 6);
      }
    }
    System.out.println(Arrays.toString(expirings));
  }

}
