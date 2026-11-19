package com.datadog.openfeature.internal.ufc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class MembershipIndexTest {
  /** Linear probing at half load averages 1.5 probes for a hit and 2.5 for a miss. */
  private static final double MAX_MEAN_HIT_PROBES = 2;

  private static final double MAX_MEAN_MISS_PROBES = 4;

  /** With well-spread keys, the longest cluster of 1,000 keys in 2,048 slots stays short. */
  private static final int MAX_PROBES = 40;

  @TableTest({
    "scenario              | first | step | count",
    "small integers        | 1     | 1    | 1000 ",
    "negative integers     | -1    | -1   | 1000 ",
    "powers of two         | 1     | x2   | 60   ",
    "prices in cent steps  | 0.01  | 0.01 | 1000 ",
    "prices ending in .99  | 0.99  | 1    | 1000 ",
    "large consecutive IDs | 1e9   | 1    | 1000 "
  })
  void spreadsNumberKeysOverTheTable(final String first, final String step, final int count) {
    // The misses are the next count numbers of the sequence.
    final double[] numbers = sequence(first, step, 2 * count);
    final double[] keys = new double[count];
    final double[] misses = new double[count];
    System.arraycopy(numbers, 0, keys, 0, count);
    System.arraycopy(numbers, count, misses, 0, count);

    assertSpread(keys, misses);
  }

  @Test
  void spreadsTheIntegersAndPowersOfTwoOfOneList() {
    final double[] keys = new double[1053];
    for (int i = 0; i < 1000; i++) {
      keys[i] = i + 1;
    }
    for (int i = 0; i < 53; i++) {
      keys[1000 + i] = Math.scalb(1.0, 10 + i);
    }
    final double[] misses = {0, -1, 1001, 1002, 1024.5, 1e6 + 3, 0.5, 0.25};

    assertSpread(keys, misses);
  }

  /** Each number is the double nearest to the decimal, as the parser reads it. */
  private static double[] sequence(final String first, final String step, final int count) {
    final double[] numbers = new double[count];
    BigDecimal number = new BigDecimal(first);
    for (int i = 0; i < count; i++) {
      numbers[i] = number.doubleValue();
      number = "x2".equals(step) ? number.add(number) : number.add(new BigDecimal(step));
    }
    return numbers;
  }

  private static void assertSpread(final double[] numbers, final double[] misses) {
    final int count = numbers.length;
    final long[] keys = new long[count];
    final int[] values = new int[count];
    for (int i = 0; i < count; i++) {
      keys[i] = Double.doubleToLongBits(numbers[i]);
      values[i] = i;
    }
    final MembershipIndex.LongIntIndex index = MembershipIndex.LongIntIndex.of(keys, values, count);

    // Replays the inserts to count the slots each lookup reads.
    final boolean[] used = new boolean[index.capacity()];
    final long[] table = new long[used.length];
    final Set<Integer> startSlots = new HashSet<>();
    for (final long key : keys) {
      final int start = index.slot(key);
      startSlots.add(start);
      int slot = start;
      while (used[slot]) {
        slot = (slot + 1) & (used.length - 1);
      }
      used[slot] = true;
      table[slot] = key;
    }

    long hitProbes = 0;
    int maxProbes = 0;
    for (int i = 0; i < count; i++) {
      assertEquals(i, index.get(keys[i]), "lookup of " + numbers[i]);
      final int probes = probes(index, used, table, keys[i]);
      hitProbes += probes;
      maxProbes = Math.max(maxProbes, probes);
    }
    long missProbes = 0;
    for (final double miss : misses) {
      final long key = Double.doubleToLongBits(miss);
      assertEquals(Integer.MAX_VALUE, index.get(key), "lookup of " + miss);
      final int probes = probes(index, used, table, key);
      missProbes += probes;
      maxProbes = Math.max(maxProbes, probes);
    }

    final String stats =
        startSlots.size()
            + " start slots, "
            + (double) hitProbes / count
            + " probes per hit, "
            + (double) missProbes / misses.length
            + " per miss, at most "
            + maxProbes
            + ", for "
            + count
            + " keys in "
            + used.length
            + " slots";
    // Linear probing degrades to a scan when the keys share a few start slots.
    assertTrue(startSlots.size() >= count / 2, stats);
    assertTrue((double) hitProbes / count <= MAX_MEAN_HIT_PROBES, stats);
    assertTrue((double) missProbes / misses.length <= MAX_MEAN_MISS_PROBES, stats);
    assertTrue(maxProbes <= MAX_PROBES, stats);
  }

  /** Counts the slots that a lookup reads, the last one included. */
  private static int probes(
      final MembershipIndex.LongIntIndex index,
      final boolean[] used,
      final long[] table,
      final long key) {
    int slot = index.slot(key);
    int probes = 1;
    while (used[slot] && table[slot] != key) {
      slot = (slot + 1) & (used.length - 1);
      probes++;
    }
    return probes;
  }
}
