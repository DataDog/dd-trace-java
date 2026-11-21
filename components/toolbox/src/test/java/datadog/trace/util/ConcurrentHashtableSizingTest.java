package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** Exercises how {@link ConcurrentHashtable#createBounded} sizes its bucket array. */
class ConcurrentHashtableSizingTest {
  private static final long MIB = 1L << 20;

  private static final class TestEntry extends ConcurrentHashtable.Entry<TestEntry> {
    final int value;

    TestEntry(int value) {
      super(value);
      this.value = value;
    }

    @Override
    public boolean matches(@Nonnull TestEntry other) {
      return value == other.value;
    }
  }

  @TableTest({
    "scenario                         | heapMiB | buckets",
    "1/1024 of a small heap           | 16      | 4096   ",
    "1/1024 of a 128 MiB heap         | 128     | 32768  ",
    "below the 1 MiB-region threshold | 1024    | 65536  ",
    "below the 2 MiB-region threshold | 4096    | 131072 ",
    "8-byte references from 32 GiB    | 65536   | 1048576",
    "below the 32 MiB-region cap      | 1048576 | 1048576",
    "never below the floor            | 1       | 1024   "
  })
  void boundsTheBucketArrayByTheHeap(final long heapMiB, final int buckets) {
    assertEquals(buckets, ConcurrentHashtable.maxBucketsForHeap(heapMiB * MIB));
  }

  @Test
  void anUnboundedHeapStillGetsAFiniteBound() {
    assertEquals(1 << 20, ConcurrentHashtable.maxBucketsForHeap(Long.MAX_VALUE));
  }

  @Test
  void smallTablesGetABucketPerEntry() {
    assertEquals(8, ConcurrentHashtable.bucketCountFor(8, 128 * MIB));
    assertEquals(1024, ConcurrentHashtable.bucketCountFor(1000, 128 * MIB));
  }

  @Test
  void largeTablesAreBoundedByTheHeap() {
    assertEquals(32768, ConcurrentHashtable.bucketCountFor(131_072, 128 * MIB));
    assertEquals(131_072, ConcurrentHashtable.bucketCountFor(131_072, 4096 * MIB));
  }

  @Test
  void theDefaultSizingUsesThisHeap() {
    final int expected =
        ConcurrentHashtable.bucketCountFor(1 << 20, Runtime.getRuntime().maxMemory());

    assertEquals(expected, ConcurrentHashtable.bucketCountFor(1 << 20));
    assertEquals(
        expected, ConcurrentHashtable.createBounded(TestEntry.class, 1 << 20).buckets.length());
  }

  @Test
  void aTableWithFewerBucketsThanEntriesFillsToItsCap() {
    final ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 100, 5);
    assertEquals(8, state.buckets.length());

    for (int key = 0; key < 100; key++) {
      try (ConcurrentHashtable.Reservation<TestEntry> r =
          ConcurrentHashtable.tryReserve(state, key)) {
        assertNotNull(r.tryGetOrInsertOrNull(key, TestEntry::new), "key " + key);
      }
    }
    assertTrue(ConcurrentHashtable.isFull(state));
    assertEquals(100, ConcurrentHashtable.estimateSize(state));

    for (int key = 0; key < 100; key++) {
      TestEntry found = null;
      for (TestEntry e = ConcurrentHashtable.bucketFor(state.buckets, key);
          e != null;
          e = e.next()) {
        if (e.value == key) {
          found = e;
        }
      }
      assertNotNull(found, "key " + key);
      try (ConcurrentHashtable.Reservation<TestEntry> r =
          ConcurrentHashtable.tryReserve(state, key)) {
        assertSame(found, r.tryGetOrInsertOrNull(key, TestEntry::new));
      }
    }
  }
}
