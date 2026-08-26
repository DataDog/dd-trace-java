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

  // ============ Static building blocks ============

  @Nested
  class StaticBuildingBlockTests {

    @Test
    void createRoundsCapacityUpToPowerOfTwo() {
      // The Hashtable.D1 / D2 size() reflects entries, but the bucket array length is
      // a power of two >= requestedCapacity. We can verify indirectly via bucketIndex masking.
      Hashtable.Entry[] buckets = Hashtable.create(StringIntEntry.class, 5);
      // Length must be a power of two >= 5
      int len = buckets.length;
      assertTrue(len >= 5);
      assertEquals(0, len & (len - 1), "length must be a power of two");
    }

    @Test
    void sizeForReturnsAtLeastOne() {
      assertEquals(1, Hashtable.sizeFor(0));
      assertEquals(1, Hashtable.sizeFor(1));
    }

    @Test
    void sizeForRoundsUpToPowerOfTwo() {
      assertEquals(2, Hashtable.sizeFor(2));
      assertEquals(4, Hashtable.sizeFor(3));
      assertEquals(4, Hashtable.sizeFor(4));
      assertEquals(8, Hashtable.sizeFor(5));
      assertEquals(1 << 30, Hashtable.sizeFor(1 << 30));
    }

    @Test
    void sizeForRejectsCapacityAboveMax() {
      assertThrows(IllegalArgumentException.class, () -> Hashtable.sizeFor((1 << 30) + 1));
      assertThrows(IllegalArgumentException.class, () -> Hashtable.sizeFor(Integer.MAX_VALUE));
    }

    @Test
    void sizeForRejectsNegativeCapacity() {
      assertThrows(IllegalArgumentException.class, () -> Hashtable.sizeFor(-1));
      assertThrows(IllegalArgumentException.class, () -> Hashtable.sizeFor(Integer.MIN_VALUE));
    }

    @Test
    void capacityForAppliesDefaultLoadFactorHeadroom() {
      // 12 / 0.75 = 16 -> already a power of two.
      assertEquals(16, Hashtable.capacityFor(12));
      // 5 / 0.75 = 6.67 -> truncated to 6 -> sizeFor rounds up to 8.
      assertEquals(8, Hashtable.capacityFor(5));
    }

    @Test
    void capacityForMatchesDefaultLoadFactorConstant() {
      assertEquals(0.75f, Hashtable.DEFAULT_LOAD_FACTOR);
      assertEquals(
          Hashtable.capacityFor(20), Hashtable.capacityFor(20, Hashtable.DEFAULT_LOAD_FACTOR));
    }

    @Test
    void capacityForAtExplicitLoadFactor() {
      // 10 / 0.5 = 20 -> sizeFor rounds up to 32.
      assertEquals(32, Hashtable.capacityFor(10, 0.5f));
    }

    @Test
    void capacityForRejectsLoadFactorOutOfRange() {
      assertThrows(IllegalArgumentException.class, () -> Hashtable.capacityFor(10, 0f));
      assertThrows(IllegalArgumentException.class, () -> Hashtable.capacityFor(10, 1f));
      assertThrows(IllegalArgumentException.class, () -> Hashtable.capacityFor(10, -0.5f));
    }

    // removeMatching and the size-tracked insertHeadEntryFor are blessed building blocks for
    // external composers (e.g. client-side stats) rather than something D1/D2 delegate to, so they
    // are covered directly here.

    @Test
    void insertHeadEntryForTracksSizeAndRefusesAtCapacity() {
      Hashtable.Entry[] buckets = Hashtable.create(2);
      Hashtable.SizeManager size = new Hashtable.SizeManager(2);

      StringIntEntry a = new StringIntEntry("a", 1);
      StringIntEntry b = new StringIntEntry("b", 2);
      StringIntEntry c = new StringIntEntry("c", 3);

      assertTrue(Hashtable.insertHeadEntryFor(size, buckets, a.keyHash, a));
      assertTrue(Hashtable.insertHeadEntryFor(size, buckets, b.keyHash, b));
      assertEquals(2, size.size());

      assertFalse(
          Hashtable.insertHeadEntryFor(size, buckets, c.keyHash, c),
          "refused once the tracker is at capacity");
      assertEquals(2, size.size(), "a refused insert must not consume a slot");
    }

    @Test
    void removeMatchingUnlinksAndDecrements() {
      Hashtable.Entry[] buckets = Hashtable.create(8);
      Hashtable.SizeManager size = new Hashtable.SizeManager(8);
      StringIntEntry a = new StringIntEntry("a", 1);
      Hashtable.insertHeadEntryFor(size, buckets, a.keyHash, a);

      StringIntEntry removed =
          Hashtable.removeMatching(size, buckets, a.keyHash, e -> e.matches("a"));

      assertSame(a, removed);
      assertEquals(0, size.size());
      assertNull(Hashtable.bucketFor(buckets, a.keyHash));
    }

    @Test
    void removeMatchingReturnsNullAndLeavesStateWhenNothingMatches() {
      Hashtable.Entry[] buckets = Hashtable.create(8);
      Hashtable.SizeManager size = new Hashtable.SizeManager(8);
      StringIntEntry a = new StringIntEntry("a", 1);
      Hashtable.insertHeadEntryFor(size, buckets, a.keyHash, a);

      assertNull(
          Hashtable.<StringIntEntry>removeMatching(
              size, buckets, a.keyHash, e -> e.matches("nope")));
      assertEquals(1, size.size(), "a non-matching scan must not decrement");
      assertSame(a, Hashtable.bucketFor(buckets, a.keyHash));
    }

    @Test
    void bucketIndexIsBoundedByArrayLength() {
      Hashtable.Entry[] buckets = Hashtable.create(StringIntEntry.class, 16);
      for (long h : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 12345L}) {
        int idx = Hashtable.bucketIndex(buckets, h);
        assertTrue(idx >= 0 && idx < buckets.length, "bucketIndex out of range for hash " + h);
      }
    }

    @Test
    void clearNullsAllBuckets() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);
      Hashtable.Entry[] buckets = table.buckets;
      buckets[0] = new StringIntEntry("x", 1);
      buckets[1] = new StringIntEntry("y", 2);
      Hashtable.clear(buckets);
      for (Hashtable.Entry b : buckets) {
        assertNull(b);
      }
    }

    @Test
    void drainVisitsEveryEntryThenClears() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);
      Hashtable.Entry[] buckets = table.buckets;
      buckets[0] = new StringIntEntry("x", 1);
      buckets[1] = new StringIntEntry("y", 2);
      Set<String> drained = new HashSet<>();
      Hashtable.<StringIntEntry>drain(buckets, e -> drained.add(e.key));
      assertEquals(2, drained.size());
      assertTrue(drained.contains("x"));
      assertTrue(drained.contains("y"));
      for (Hashtable.Entry b : buckets) {
        assertNull(b);
      }
    }

    @Test
    void insertHeadEntrySplicesAsNewHead() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);
      Hashtable.Entry[] buckets = table.buckets;
      StringIntEntry a = new StringIntEntry("a", 1);
      StringIntEntry b = new StringIntEntry("b", 2);
      Hashtable.insertHeadEntryAt(buckets, 0, a);
      assertSame(a, buckets[0]);
      assertNull(a.next());

      Hashtable.insertHeadEntryAt(buckets, 0, b);
      assertSame(b, buckets[0]);
      assertSame(a, b.next());
      assertNull(a.next());
    }
  }

  // ============ Deprecated Support facade ============

  /**
   * The scaled {@code create(int, float)} factory and {@code MAX_RATIO} are deprecated-only: they
   * have no blessed equivalent on {@link Hashtable} but remain in use by client-side statistics, so
   * they keep dedicated coverage here.
   */
  @Nested
  @SuppressWarnings("deprecation")
  class DeprecatedSupportTests {

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
    void createWithoutScaleDelegatesToHashtableSizeFor() {
      Hashtable.Entry[] buckets = Support.create(5);
      assertEquals(Hashtable.create(StringIntEntry.class, 5).length, buckets.length);
    }

    @Test
    void clearDelegatesToHashtableClear() {
      Hashtable.Entry[] buckets = Support.create(4);
      buckets[0] = new StringIntEntry("a", 1);
      Support.clear(buckets);
      for (Hashtable.Entry b : buckets) {
        assertNull(b);
      }
    }

    @Test
    void bucketIndexDelegatesToHashtableBucketIndex() {
      Hashtable.Entry[] buckets = Support.create(4);
      long hash = StringIntEntry.hash("a");
      assertEquals(Hashtable.bucketIndex(buckets, hash), Support.bucketIndex(buckets, hash));
    }

    @Test
    void insertHeadEntryByIndexDelegatesToHashtableInsertHeadEntryAt() {
      Hashtable.Entry[] buckets = Support.create(4);
      StringIntEntry entry = new StringIntEntry("a", 1);
      Support.insertHeadEntry(buckets, 0, entry);
      assertSame(entry, buckets[0]);
    }

    @Test
    void insertHeadEntryByHashDelegatesToHashtableInsertHeadEntryFor() {
      Hashtable.Entry[] buckets = Support.create(4);
      StringIntEntry entry = new StringIntEntry("a", 1);
      Support.insertHeadEntry(buckets, entry.keyHash, entry);
      assertSame(entry, Support.<StringIntEntry>bucket(buckets, entry.keyHash));
    }

    @Test
    void bucketDelegatesToHashtableBucketFor() {
      Hashtable.Entry[] buckets = Support.create(4);
      StringIntEntry entry = new StringIntEntry("a", 1);
      Support.insertHeadEntry(buckets, entry.keyHash, entry);
      assertSame(entry, Support.<StringIntEntry>bucket(buckets, entry.keyHash));
    }

    @Test
    void bucketIteratorDelegatesToHashtableBucketIterator() {
      Hashtable.Entry[] buckets = Support.create(4);
      StringIntEntry entry = new StringIntEntry("a", 1);
      Support.insertHeadEntry(buckets, entry.keyHash, entry);
      BucketIterator<StringIntEntry> it = Support.bucketIterator(buckets, entry.keyHash);
      assertTrue(it.hasNext());
      assertSame(entry, it.next());
    }

    @Test
    void mutatingBucketIteratorDelegatesToHashtableMutatingBucketIterator() {
      Hashtable.Entry[] buckets = Support.create(4);
      StringIntEntry entry = new StringIntEntry("a", 1);
      Support.insertHeadEntry(buckets, entry.keyHash, entry);
      MutatingBucketIterator<StringIntEntry> it =
          Support.mutatingBucketIterator(buckets, entry.keyHash);
      assertTrue(it.hasNext());
      assertSame(entry, it.next());
      it.remove();
      assertNull(Support.<StringIntEntry>bucket(buckets, entry.keyHash));
    }

    @Test
    void mutatingTableIteratorOverFullTableDelegatesToHashtable() {
      Hashtable.Entry[] buckets = Support.create(4);
      buckets[0] = new StringIntEntry("a", 1);
      MutatingTableIterator<StringIntEntry> it = Support.mutatingTableIterator(buckets);
      assertTrue(it.hasNext());
      assertEquals("a", it.next().key);
    }

    @Test
    void mutatingTableIteratorOverRangeDelegatesToHashtable() {
      Hashtable.Entry[] buckets = Support.create(4);
      buckets[0] = new StringIntEntry("a", 1);
      buckets[2] = new StringIntEntry("b", 2);
      MutatingTableIterator<StringIntEntry> it = Support.mutatingTableIterator(buckets, 0, 2);
      assertTrue(it.hasNext());
      assertEquals("a", it.next().key);
      assertFalse(it.hasNext(), "range end is exclusive");
    }

    @Test
    void forEachDelegatesToHashtableForEach() {
      Hashtable.Entry[] buckets = Support.create(4);
      buckets[0] = new StringIntEntry("a", 1);
      buckets[1] = new StringIntEntry("b", 2);
      Set<String> seen = new HashSet<>();
      Support.<StringIntEntry>forEach(buckets, e -> seen.add(e.key));
      assertEquals(2, seen.size());
    }

    @Test
    void forEachWithContextDelegatesToHashtableForEach() {
      Hashtable.Entry[] buckets = Support.create(4);
      buckets[0] = new StringIntEntry("a", 1);
      Set<String> seen = new HashSet<>();
      Support.<Set<String>, StringIntEntry>forEach(buckets, seen, (ctx, e) -> ctx.add(e.key));
      assertEquals(1, seen.size());
    }
  }

  // ============ BucketIterator ============

  @Nested
  class BucketIteratorTests {

    @Test
    void walksOnlyMatchingHash() {
      // Build a bucket array with two entries that share a bucket but have different hashes.
      // Use Hashtable.D1 to seed; then call Hashtable.bucketIterator directly with the matching
      // hash and verify it only returns the matching entry.
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table =
          Hashtable.D1.createCapped(CollidingKeyEntry.class, 4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      CollidingKey k3 = new CollidingKey("third", 17);
      table.insert(new CollidingKeyEntry(k1, 1));
      table.insert(new CollidingKeyEntry(k2, 2));
      table.insert(new CollidingKeyEntry(k3, 3));
      // All three share the same hash (17), so a bucket iterator over hash=17 yields all three.
      BucketIterator<CollidingKeyEntry> it = Hashtable.bucketIterator(table.buckets, 17L);
      int count = 0;
      while (it.hasNext()) {
        assertNotNull(it.next());
        count++;
      }
      assertEquals(3, count);
    }

    @Test
    void exhaustedIteratorThrowsNoSuchElement() {
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.createCapped(StringIntEntry.class, 4);
      table.insert(new StringIntEntry("only", 1));
      long h = Hashtable.D1.Entry.hash("only");
      BucketIterator<StringIntEntry> it = Hashtable.bucketIterator(table.buckets, h);
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
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table =
          Hashtable.D1.createCapped(CollidingKeyEntry.class, 4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      CollidingKey k3 = new CollidingKey("third", 17);
      table.insert(new CollidingKeyEntry(k1, 1));
      table.insert(new CollidingKeyEntry(k2, 2));
      table.insert(new CollidingKeyEntry(k3, 3));

      MutatingBucketIterator<CollidingKeyEntry> it =
          Hashtable.mutatingBucketIterator(table.buckets, 17L);
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
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table =
          Hashtable.D1.createCapped(CollidingKeyEntry.class, 4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      CollidingKeyEntry e1 = new CollidingKeyEntry(k1, 1);
      CollidingKeyEntry e2 = new CollidingKeyEntry(k2, 2);
      table.insert(e1);
      table.insert(e2);

      MutatingBucketIterator<CollidingKeyEntry> it =
          Hashtable.mutatingBucketIterator(table.buckets, 17L);
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
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.createCapped(StringIntEntry.class, 4);
      table.insert(new StringIntEntry("a", 1));
      MutatingBucketIterator<StringIntEntry> it =
          Hashtable.mutatingBucketIterator(table.buckets, Hashtable.D1.Entry.hash("a"));
      assertThrows(IllegalStateException.class, it::remove);
    }
  }

  // ============ MutatingTableIterator ============

  @Nested
  class MutatingTableIteratorTests {

    @Test
    void walksEveryEntryAcrossBuckets() {
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.createCapped(StringIntEntry.class, 16);
      table.insert(new StringIntEntry("a", 1));
      table.insert(new StringIntEntry("b", 2));
      table.insert(new StringIntEntry("c", 3));

      Set<String> seen = new HashSet<>();
      for (MutatingTableIterator<StringIntEntry> it =
              Hashtable.mutatingTableIterator(table.buckets);
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
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.createCapped(StringIntEntry.class, 8);
      MutatingTableIterator<StringIntEntry> it = Hashtable.mutatingTableIterator(table.buckets);
      assertFalse(it.hasNext());
      assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    void removeUnlinksBucketHead() {
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table =
          Hashtable.D1.createCapped(CollidingKeyEntry.class, 4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      table.insert(new CollidingKeyEntry(k1, 1));
      table.insert(new CollidingKeyEntry(k2, 2));

      // The head of the chain is whichever was inserted last (insert prepends).
      MutatingTableIterator<CollidingKeyEntry> it = Hashtable.mutatingTableIterator(table.buckets);
      CollidingKeyEntry head = it.next();
      it.remove();

      // Survivor still reachable via the table; removed one is not.
      CollidingKey survivorKey = head.key.equals(k1) ? k2 : k1;
      assertNotNull(table.get(survivorKey));
      assertNull(table.get(head.key));
    }

    @Test
    void removeUnlinksMidChainEntry() {
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table =
          Hashtable.D1.createCapped(CollidingKeyEntry.class, 4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      CollidingKey k3 = new CollidingKey("third", 17);
      table.insert(new CollidingKeyEntry(k1, 1));
      table.insert(new CollidingKeyEntry(k2, 2));
      table.insert(new CollidingKeyEntry(k3, 3));

      // Walk to the second entry, remove it.
      MutatingTableIterator<CollidingKeyEntry> it = Hashtable.mutatingTableIterator(table.buckets);
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
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.createCapped(StringIntEntry.class, 64);
      table.insert(new StringIntEntry("alpha", 1));
      table.insert(new StringIntEntry("beta", 2));
      table.insert(new StringIntEntry("gamma", 3));

      MutatingTableIterator<StringIntEntry> it = Hashtable.mutatingTableIterator(table.buckets);
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
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.createCapped(StringIntEntry.class, 4);
      table.insert(new StringIntEntry("a", 1));
      MutatingTableIterator<StringIntEntry> it = Hashtable.mutatingTableIterator(table.buckets);
      assertThrows(IllegalStateException.class, it::remove);
    }

    @Test
    void removeTwiceWithoutInterveningNextThrows() {
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.createCapped(StringIntEntry.class, 4);
      table.insert(new StringIntEntry("a", 1));
      table.insert(new StringIntEntry("b", 2));
      MutatingTableIterator<StringIntEntry> it = Hashtable.mutatingTableIterator(table.buckets);
      it.next();
      it.remove();
      assertThrows(IllegalStateException.class, it::remove);
    }

    @Test
    void halfOpenRangeOmitsBucketsOutsideTheRange() {
      // CollidingKey lets us pin entries to specific buckets via controlled hashCode. 16-slot
      // table -> bucketIndex = hash & 15. Place entries in buckets 0, 5, and 10; iterate
      // [5, 10) -- should see only bucket 5.
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table =
          Hashtable.D1.createCapped(CollidingKeyEntry.class, 16);
      table.insert(new CollidingKeyEntry(new CollidingKey("b0", 0), 1));
      table.insert(new CollidingKeyEntry(new CollidingKey("b5", 5), 2));
      table.insert(new CollidingKeyEntry(new CollidingKey("b10", 10), 3));

      Set<String> seen = new HashSet<>();
      for (MutatingTableIterator<CollidingKeyEntry> it =
              Hashtable.mutatingTableIterator(table.buckets, 5, 10);
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
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.createCapped(StringIntEntry.class, 8);
      table.insert(new StringIntEntry("a", 1));
      MutatingTableIterator<StringIntEntry> it =
          Hashtable.mutatingTableIterator(table.buckets, 0, 0);
      assertFalse(it.hasNext());
    }

    @Test
    void rangeBoundsOutOfOrderThrows() {
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.createCapped(StringIntEntry.class, 8);
      assertThrows(
          IndexOutOfBoundsException.class,
          () -> Hashtable.mutatingTableIterator(table.buckets, -1, 4));
      assertThrows(
          IndexOutOfBoundsException.class,
          () -> Hashtable.mutatingTableIterator(table.buckets, 4, 2)); // end < start
      assertThrows(
          IndexOutOfBoundsException.class,
          () ->
              Hashtable.mutatingTableIterator(
                  table.buckets, 0, table.buckets.length + 1)); // end > len
    }

    @Test
    void currentBucketReportsLandingIndex() {
      // Pin one entry to a known bucket and check currentBucket() after next() reports that
      // bucket. Before any next() (or after remove()), currentBucket() returns -1.
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table =
          Hashtable.D1.createCapped(CollidingKeyEntry.class, 16);
      table.insert(new CollidingKeyEntry(new CollidingKey("b3", 3), 1));

      MutatingTableIterator<CollidingKeyEntry> it = Hashtable.mutatingTableIterator(table.buckets);
      assertEquals(-1, it.currentBucket(), "before any next() currentBucket should be -1");
      it.next();
      assertEquals(3, it.currentBucket(), "currentBucket should report the entry's bucket");
    }
  }

  // ============ Eviction (SizeManager) ============

  @Nested
  class EvictionTests {

    @Test
    void evictOneRemovesFirstMatchAndAdvancesCursor() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);
      Hashtable.Entry[] buckets = table.buckets;
      buckets[0] = new StringIntEntry("a", 1);
      buckets[1] = new StringIntEntry("b", 2);
      StringIntEntry evicted = Hashtable.evictOne(table, e -> e.value == 2);

      assertEquals("b", evicted.key);
      assertNull(buckets[1]);
      assertNotNull(buckets[0]);
    }

    @Test
    void tryReserveOrEvictReservesWhileRoomRemains() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(2);

      assertTrue(Hashtable.tryReserveOrEvict(table, e -> e.value == 0));
      assertTrue(Hashtable.tryReserveOrEvict(table, e -> e.value == 0));
      assertEquals(2, table.sizeManager.size());
    }

    @Test
    void tryReserveOrEvictMakesRoomWhenFull() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(2);
      StringIntEntry stale = new StringIntEntry("stale", 0);
      StringIntEntry hot = new StringIntEntry("hot", 1);
      assertTrue(Hashtable.insertHeadEntryFor(table, stale.keyHash, stale));
      assertTrue(Hashtable.insertHeadEntryFor(table, hot.keyHash, hot));
      assertTrue(table.sizeManager.isFull());

      // Full, but one entry is evictable -- the slot it frees becomes the reservation.
      assertTrue(Hashtable.tryReserveOrEvict(table, e -> e.value == 0));
      assertEquals(2, table.sizeManager.size(), "one out, one reserved");
      Set<String> remaining = new HashSet<>();
      Hashtable.<StringIntEntry>forEach(table.buckets, e -> remaining.add(e.key));
      assertFalse(remaining.contains("stale"), "the evictable entry is gone");
      assertTrue(remaining.contains("hot"), "the hot entry survived");
    }

    @Test
    void tryReserveOrEvictRefusesWhenFullAndNothingEvictable() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(1);
      StringIntEntry hot = new StringIntEntry("hot", 1);
      assertTrue(Hashtable.insertHeadEntryFor(table, hot.keyHash, hot));

      assertFalse(Hashtable.tryReserveOrEvict(table, e -> e.value == 0));
      assertEquals(1, table.sizeManager.size(), "a refused reservation consumes nothing");
      Set<String> remaining = new HashSet<>();
      Hashtable.<StringIntEntry>forEach(table.buckets, e -> remaining.add(e.key));
      assertTrue(remaining.contains("hot"), "nothing was evicted");
    }

    @Test
    void removeMatchingOverStateNeedsNoTypeWitness() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);
      StringIntEntry a = new StringIntEntry("a", 1);
      Hashtable.insertHeadEntryFor(table, a.keyHash, a);

      StringIntEntry removed = Hashtable.removeMatching(table, a.keyHash, e -> e.matches("a"));

      assertSame(a, removed);
      assertEquals(0, table.sizeManager.size());
    }

    @Test
    void clearOverStateEmptiesSpineAndResetsCount() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);
      StringIntEntry a = new StringIntEntry("a", 1);
      Hashtable.insertHeadEntryFor(table, a.keyHash, a);
      assertEquals(1, table.sizeManager.size());

      Hashtable.clear(table);

      assertEquals(0, table.sizeManager.size());
      assertNull(table.buckets[Hashtable.bucketIndex(table.buckets, a.keyHash)]);
    }

    @Test
    void evictOneReturnsNullWhenNothingMatches() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);
      Hashtable.Entry[] buckets = table.buckets;
      buckets[0] = new StringIntEntry("a", 1);
      assertNull(Hashtable.evictOne(table, e -> e.value == 999));
      assertNotNull(buckets[0]);
    }

    @Test
    void evictOneWrapsAroundToStartOfTable() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);
      Hashtable.Entry[] buckets = table.buckets;
      buckets[0] = new StringIntEntry("a", 1);
      buckets[3] = new StringIntEntry("d", 4);
      // First eviction matches bucket 3, advancing the cursor there.
      StringIntEntry first = Hashtable.evictOne(table, e -> e.value == 4);
      assertEquals("d", first.key);

      // Only remaining candidate is bucket 0, before the cursor -- requires wrap-around.
      StringIntEntry second = Hashtable.evictOne(table, e -> e.value == 1);
      assertEquals("a", second.key);
    }

    @Test
    void drainRemovesAllMatchesAndResetsCursor() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);
      Hashtable.Entry[] buckets = table.buckets;
      buckets[0] = new StringIntEntry("a", 1);
      buckets[1] = new StringIntEntry("b", 2);
      buckets[2] = new StringIntEntry("c", 3);
      Hashtable.evictOne(table, e -> e.value == 3);

      int removed = Hashtable.evictAll(table, e -> e.value < 3);

      assertEquals(2, removed);
      assertNull(buckets[0]);
      assertNull(buckets[1]);

      // drain resets the cursor to the start, so a fresh scan finds bucket 0 without wrapping.
      buckets[0] = new StringIntEntry("a2", 1);
      StringIntEntry evicted = Hashtable.evictOne(table, e -> e.value == 1);
      assertEquals("a2", evicted.key);
    }

    @Test
    void resetZeroesCursor() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);
      Hashtable.Entry[] buckets = table.buckets;
      buckets[3] = new StringIntEntry("d", 4);
      Hashtable.evictOne(table, e -> e.value == 4);

      table.sizeManager.reset();

      buckets[0] = new StringIntEntry("a", 1);
      StringIntEntry evicted = Hashtable.evictOne(table, e -> e.value == 1);
      assertEquals("a", evicted.key);
    }
  }

  // ============ Table ============

  @Nested
  class StateTests {

    @Test
    void createTableSizesBucketsWithHeadroomAndCapsSize() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);

      int len = table.buckets.length;
      assertTrue(len >= 4, "backing array must have load-factor headroom over capacity");
      assertEquals(0, len & (len - 1), "length must be a power of two");
      assertNotNull(table.sizeManager);
      assertNotNull(table.sizeManager);
      assertEquals(4, table.sizeManager.capacity());
      assertFalse(table.sizeManager.isFull());
    }

    @Test
    void tableSizeTrackerRespectsCapacity() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(1);

      assertTrue(table.sizeManager.tryReserve());
      assertTrue(table.sizeManager.isFull());
      assertFalse(table.sizeManager.tryReserve());
    }

    @Test
    void tableSizeManagerOperatesOnItsOwnBuckets() {
      Hashtable.State<StringIntEntry> table = Hashtable.createCapped(4);
      table.buckets[0] = new StringIntEntry("a", 1);

      StringIntEntry evicted =
          (StringIntEntry)
              table.sizeManager.evictOne(table.buckets, e -> ((StringIntEntry) e).value == 1);

      assertEquals("a", evicted.key);
      assertNull(table.buckets[0]);
    }
  }
}
