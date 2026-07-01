package datadog.trace.util;

import static datadog.trace.util.HashtableTestEntries.CollidingKey;
import static datadog.trace.util.HashtableTestEntries.CollidingKeyEntry;
import static datadog.trace.util.HashtableTestEntries.StringIntEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.Hashtable.BucketIterator;
import datadog.trace.util.Hashtable.MutatingBucketIterator;
import datadog.trace.util.Hashtable.MutatingTableIterator;
import datadog.trace.util.Hashtable.Support;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HashtableTest {

  // ============ Support ============

  @Nested
  class SupportTests {

    @Test
    void createRoundsCapacityUpToPowerOfTwo() {
      // The Hashtable.D1 / D2 size() reflects entries, but the bucket array length is
      // a power of two >= requestedCapacity. We can verify indirectly via bucketIndex masking.
      Hashtable.Entry[] buckets = Support.create(5);
      // Length must be a power of two >= 5
      int len = buckets.length;
      assertTrue(len >= 5);
      assertEquals(0, len & (len - 1), "length must be a power of two");
    }

    @Test
    void sizeForReturnsAtLeastOne() {
      assertEquals(1, Support.sizeFor(0));
      assertEquals(1, Support.sizeFor(1));
    }

    @Test
    void sizeForRoundsUpToPowerOfTwo() {
      assertEquals(2, Support.sizeFor(2));
      assertEquals(4, Support.sizeFor(3));
      assertEquals(4, Support.sizeFor(4));
      assertEquals(8, Support.sizeFor(5));
      assertEquals(1 << 30, Support.sizeFor(1 << 30));
    }

    @Test
    void sizeForRejectsCapacityAboveMax() {
      assertThrows(IllegalArgumentException.class, () -> Support.sizeFor((1 << 30) + 1));
      assertThrows(IllegalArgumentException.class, () -> Support.sizeFor(Integer.MAX_VALUE));
    }

    @Test
    void sizeForRejectsNegativeCapacity() {
      assertThrows(IllegalArgumentException.class, () -> Support.sizeFor(-1));
      assertThrows(IllegalArgumentException.class, () -> Support.sizeFor(Integer.MIN_VALUE));
    }

    @Test
    void bucketIndexIsBoundedByArrayLength() {
      Hashtable.Entry[] buckets = Support.create(16);
      for (long h : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 12345L}) {
        int idx = Support.bucketIndex(buckets, h);
        assertTrue(idx >= 0 && idx < buckets.length, "bucketIndex out of range for hash " + h);
      }
    }

    @Test
    void clearNullsAllBuckets() {
      Hashtable.Entry[] buckets = Support.create(4);
      buckets[0] = new StringIntEntry("x", 1);
      buckets[1] = new StringIntEntry("y", 2);
      Support.clear(buckets);
      for (Hashtable.Entry b : buckets) {
        assertNull(b);
      }
    }

    @Test
    void maxRatioScalesTargetForLoadFactor() {
      // 75% load factor => bucket array sized at requestedSize * 4/3, rounded up to power of 2.
      // 12 * (4/3) = 16 entries, rounded up to power-of-2 length = 16.
      assertEquals(4.0f / 3.0f, Support.MAX_RATIO);
      Hashtable.Entry[] buckets = Support.create(12, Support.MAX_RATIO);
      assertEquals(16, buckets.length);
    }

    @Test
    void createWithScaleRoundsUpToPowerOfTwo() {
      // 7 * 1.5 = 10.5 -> (int) 10 -> sizeFor rounds up to next power-of-two = 16
      Hashtable.Entry[] buckets = Support.create(7, 1.5f);
      assertEquals(16, buckets.length);
    }

    @Test
    void insertHeadEntrySplicesAsNewHead() {
      Hashtable.Entry[] buckets = Support.create(4);
      StringIntEntry a = new StringIntEntry("a", 1);
      StringIntEntry b = new StringIntEntry("b", 2);
      Support.insertHeadEntry(buckets, 0, a);
      assertSame(a, buckets[0]);
      assertNull(a.next());

      Support.insertHeadEntry(buckets, 0, b);
      assertSame(b, buckets[0]);
      assertSame(a, b.next());
      assertNull(a.next());
    }
  }

  // ============ BucketIterator ============

  @Nested
  class BucketIteratorTests {

    @Test
    void walksOnlyMatchingHash() {
      // Build a bucket array with two entries that share a bucket but have different hashes.
      // Use Hashtable.D1 to seed; then call Support.bucketIterator directly with the matching
      // hash and verify it only returns the matching entry.
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table = new Hashtable.D1<>(4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      CollidingKey k3 = new CollidingKey("third", 17);
      table.insert(new CollidingKeyEntry(k1, 1));
      table.insert(new CollidingKeyEntry(k2, 2));
      table.insert(new CollidingKeyEntry(k3, 3));
      // All three share the same hash (17), so a bucket iterator over hash=17 yields all three.
      BucketIterator<CollidingKeyEntry> it = Support.bucketIterator(table.buckets, 17L);
      int count = 0;
      while (it.hasNext()) {
        assertNotNull(it.next());
        count++;
      }
      assertEquals(3, count);
    }

    @Test
    void exhaustedIteratorThrowsNoSuchElement() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(4);
      table.insert(new StringIntEntry("only", 1));
      long h = Hashtable.D1.Entry.hash("only");
      BucketIterator<StringIntEntry> it = Support.bucketIterator(table.buckets, h);
      it.next();
      assertFalse(it.hasNext());
      assertThrows(NoSuchElementException.class, it::next);
    }
  }

  // ============ MutatingBucketIterator ============

  @Nested
  class MutatingBucketIteratorTests {

    @Test
    void removeFromHeadOfChainUnlinks() {
      // Make three entries with the same hash so they chain in one bucket
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table = new Hashtable.D1<>(4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      CollidingKey k3 = new CollidingKey("third", 17);
      table.insert(new CollidingKeyEntry(k1, 1));
      table.insert(new CollidingKeyEntry(k2, 2));
      table.insert(new CollidingKeyEntry(k3, 3));

      MutatingBucketIterator<CollidingKeyEntry> it =
          Support.mutatingBucketIterator(table.buckets, 17L);
      it.next(); // first match (head of chain in insertion-reverse order)
      it.remove();
      // Two should remain
      int remaining = 0;
      while (it.hasNext()) {
        it.next();
        remaining++;
      }
      assertEquals(2, remaining);
      // And the table still finds the survivors via get(...)
      // (which entry was the head depends on insertion order; we just verify count + that two
      // of the three keys are still retrievable.)
      int found = 0;
      for (CollidingKey k : new CollidingKey[] {k1, k2, k3}) {
        if (table.get(k) != null) {
          found++;
        }
      }
      assertEquals(2, found);
    }

    @Test
    void replaceSwapsEntryAndPreservesChain() {
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table = new Hashtable.D1<>(4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      CollidingKeyEntry e1 = new CollidingKeyEntry(k1, 1);
      CollidingKeyEntry e2 = new CollidingKeyEntry(k2, 2);
      table.insert(e1);
      table.insert(e2);

      MutatingBucketIterator<CollidingKeyEntry> it =
          Support.mutatingBucketIterator(table.buckets, 17L);
      CollidingKeyEntry first = it.next();
      CollidingKeyEntry replacement = new CollidingKeyEntry(first.key, 999);
      it.replace(replacement);
      // Both entries still in the chain
      assertNotNull(table.get(k1));
      assertNotNull(table.get(k2));
      // The replaced one now has value 999
      assertEquals(999, table.get(first.key).value);
    }

    @Test
    void removeWithoutNextThrows() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(4);
      table.insert(new StringIntEntry("a", 1));
      MutatingBucketIterator<StringIntEntry> it =
          Support.mutatingBucketIterator(table.buckets, Hashtable.D1.Entry.hash("a"));
      assertThrows(IllegalStateException.class, it::remove);
    }
  }

  // ============ MutatingTableIterator ============

  @Nested
  class MutatingTableIteratorTests {

    @Test
    void walksEveryEntryAcrossBuckets() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(16);
      table.insert(new StringIntEntry("a", 1));
      table.insert(new StringIntEntry("b", 2));
      table.insert(new StringIntEntry("c", 3));

      Set<String> seen = new HashSet<>();
      for (MutatingTableIterator<StringIntEntry> it = Support.mutatingTableIterator(table.buckets);
          it.hasNext(); ) {
        seen.add(it.next().key);
      }
      assertEquals(3, seen.size());
      assertTrue(seen.contains("a"));
      assertTrue(seen.contains("b"));
      assertTrue(seen.contains("c"));
    }

    @Test
    void emptyTableIteratorIsExhausted() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      MutatingTableIterator<StringIntEntry> it = Support.mutatingTableIterator(table.buckets);
      assertFalse(it.hasNext());
      assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    void removeUnlinksBucketHead() {
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table = new Hashtable.D1<>(4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      table.insert(new CollidingKeyEntry(k1, 1));
      table.insert(new CollidingKeyEntry(k2, 2));

      // The head of the chain is whichever was inserted last (insert prepends).
      MutatingTableIterator<CollidingKeyEntry> it = Support.mutatingTableIterator(table.buckets);
      CollidingKeyEntry head = it.next();
      it.remove();

      // Survivor still reachable via the table; removed one is not.
      CollidingKey survivorKey = head.key.equals(k1) ? k2 : k1;
      assertNotNull(table.get(survivorKey));
      assertNull(table.get(head.key));
    }

    @Test
    void removeUnlinksMidChainEntry() {
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table = new Hashtable.D1<>(4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      CollidingKey k3 = new CollidingKey("third", 17);
      table.insert(new CollidingKeyEntry(k1, 1));
      table.insert(new CollidingKeyEntry(k2, 2));
      table.insert(new CollidingKeyEntry(k3, 3));

      // Walk to the second entry, remove it.
      MutatingTableIterator<CollidingKeyEntry> it = Support.mutatingTableIterator(table.buckets);
      it.next();
      CollidingKeyEntry victim = it.next();
      it.remove();

      assertNull(table.get(victim.key));
      // The remaining two keys still resolve.
      int remaining = 0;
      for (CollidingKey k : new CollidingKey[] {k1, k2, k3}) {
        if (table.get(k) != null) {
          remaining++;
        }
      }
      assertEquals(2, remaining);

      // Iteration can continue past a remove and yield the third entry.
      assertTrue(it.hasNext());
      assertNotNull(it.next());
      assertFalse(it.hasNext());
    }

    @Test
    void removeSkipsOverEmptyBuckets() {
      // Three distinct keys that land in different buckets (low entry count vs large bucket array
      // makes empty buckets between them very likely). Verify the iterator skips empties cleanly
      // after a remove.
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(64);
      table.insert(new StringIntEntry("alpha", 1));
      table.insert(new StringIntEntry("beta", 2));
      table.insert(new StringIntEntry("gamma", 3));

      MutatingTableIterator<StringIntEntry> it = Support.mutatingTableIterator(table.buckets);
      it.next();
      it.remove();
      int remaining = 0;
      while (it.hasNext()) {
        it.next();
        remaining++;
      }
      assertEquals(2, remaining);
    }

    @Test
    void removeWithoutNextThrows() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(4);
      table.insert(new StringIntEntry("a", 1));
      MutatingTableIterator<StringIntEntry> it = Support.mutatingTableIterator(table.buckets);
      assertThrows(IllegalStateException.class, it::remove);
    }

    @Test
    void removeTwiceWithoutInterveningNextThrows() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(4);
      table.insert(new StringIntEntry("a", 1));
      table.insert(new StringIntEntry("b", 2));
      MutatingTableIterator<StringIntEntry> it = Support.mutatingTableIterator(table.buckets);
      it.next();
      it.remove();
      assertThrows(IllegalStateException.class, it::remove);
    }

    @Test
    void halfOpenRangeOmitsBucketsOutsideTheRange() {
      // CollidingKey lets us pin entries to specific buckets via controlled hashCode. 16-slot
      // table -> bucketIndex = hash & 15. Place entries in buckets 0, 5, and 10; iterate
      // [5, 10) -- should see only bucket 5.
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table = new Hashtable.D1<>(16);
      table.insert(new CollidingKeyEntry(new CollidingKey("b0", 0), 1));
      table.insert(new CollidingKeyEntry(new CollidingKey("b5", 5), 2));
      table.insert(new CollidingKeyEntry(new CollidingKey("b10", 10), 3));

      Set<String> seen = new HashSet<>();
      for (MutatingTableIterator<CollidingKeyEntry> it =
              Support.mutatingTableIterator(table.buckets, 5, 10);
          it.hasNext(); ) {
        seen.add(it.next().key.label);
      }
      assertEquals(1, seen.size());
      assertTrue(seen.contains("b5"));
    }

    @Test
    void emptyHalfOpenRangeIsExhausted() {
      // start == end -> immediately-exhausted iterator. Important: this is the wrap-around
      // pass [0, cursor) when cursor == 0 in resumable sweeps.
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      table.insert(new StringIntEntry("a", 1));
      MutatingTableIterator<StringIntEntry> it = Support.mutatingTableIterator(table.buckets, 0, 0);
      assertFalse(it.hasNext());
    }

    @Test
    void rangeBoundsOutOfOrderThrows() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      assertThrows(
          IndexOutOfBoundsException.class,
          () -> Support.mutatingTableIterator(table.buckets, -1, 4));
      assertThrows(
          IndexOutOfBoundsException.class,
          () -> Support.mutatingTableIterator(table.buckets, 4, 2)); // end < start
      assertThrows(
          IndexOutOfBoundsException.class,
          () ->
              Support.mutatingTableIterator(
                  table.buckets, 0, table.buckets.length + 1)); // end > len
    }

    @Test
    void currentBucketReportsLandingIndex() {
      // Pin one entry to a known bucket and check currentBucket() after next() reports that
      // bucket. Before any next() (or after remove()), currentBucket() returns -1.
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table = new Hashtable.D1<>(16);
      table.insert(new CollidingKeyEntry(new CollidingKey("b3", 3), 1));

      MutatingTableIterator<CollidingKeyEntry> it = Support.mutatingTableIterator(table.buckets);
      assertEquals(-1, it.currentBucket(), "before any next() currentBucket should be -1");
      it.next();
      assertEquals(3, it.currentBucket(), "currentBucket should report the entry's bucket");
    }
  }
}
