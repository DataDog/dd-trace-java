package com.datadog.openfeature.internal.ufc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.tabletest.junit.TableTest;

class MembershipIndexTest {
  @TableTest({
    "scenario              | first | step | count",
    "small integers        | 1     | 1    | 1000 ",
    "powers of two         | 1     | 2    | 60   ",
    "prices in cent steps  | 0.01  | 0.01 | 1000 ",
    "large consecutive IDs | 1e9   | 1    | 1000 "
  })
  void spreadsNumberKeysOverTheTable(final double first, final double step, final int count) {
    final long[] keys = new long[count];
    final int[] values = new int[count];
    double number = first;
    for (int i = 0; i < count; i++) {
      keys[i] = Double.doubleToLongBits(number);
      values[i] = i;
      number = step == 2 ? number * 2 : number + step;
    }
    final MembershipIndex.LongIntIndex index = MembershipIndex.LongIntIndex.of(keys, values, count);

    final Set<Integer> slots = new HashSet<>();
    for (int i = 0; i < count; i++) {
      slots.add(index.slot(keys[i]));
      assertEquals(i, index.get(keys[i]));
    }
    // Linear probing degrades to a scan when the keys share a few start slots.
    assertTrue(slots.size() >= count / 2, slots.size() + " start slots for " + count + " keys");
  }
}
