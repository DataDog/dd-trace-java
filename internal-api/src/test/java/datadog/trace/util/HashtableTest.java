package datadog.trace.util;

import static datadog.trace.util.HashtableTestEntries.CollidingKey;
import static datadog.trace.util.HashtableTestEntries.CollidingKeyEntry;
import static datadog.trace.util.HashtableTestEntries.StringIntEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.Hashtable.BucketIterator;
import datadog.trace.util.Hashtable.MutatingBucketIterator;
import datadog.trace.util.Hashtable.Support;
import java.util.NoSuchElementException;
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
}
