package com.datadog.profiling.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimeBucketsTest {
  private TimeBuckets.Bucket<String> instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new TimeBuckets.Bucket<>(3);
  }

  @Test
  void test() throws Exception {
    int p = instance.add("a");
    int q = instance.add("b");
    int r = instance.add("c");
    int s = instance.add("d");

    instance.remove(p);
    instance.remove(q);
    instance.remove(r);
  }

  @Test
  void testStriped() throws Exception {
    TimeBuckets.StripedBucket<String> bucket = new TimeBuckets.StripedBucket<>(2, 4);

    int i1 = bucket.add("a");
    int i2 = bucket.add("b");
    int i3 = bucket.add("c");
    int i4 = bucket.add("d");
    int i5 = bucket.add("e");

    System.out.println("xxx");
  }

}
