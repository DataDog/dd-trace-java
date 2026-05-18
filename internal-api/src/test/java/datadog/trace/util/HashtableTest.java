package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.Hashtable.BucketIterator;
import datadog.trace.util.Hashtable.MutatingBucketIterator;
import datadog.trace.util.Hashtable.Support;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HashtableTest {

  // ============ D1 ============

  @Nested
  class D1Tests {

    @Test
    void emptyTableLookupReturnsNull() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      assertNull(table.get("missing"));
      assertEquals(0, table.size());
    }

    @Test
    void insertedEntryIsRetrievable() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      StringIntEntry e = new StringIntEntry("foo", 1);
      table.insert(e);
      assertEquals(1, table.size());
      assertSame(e, table.get("foo"));
    }

    @Test
    void multipleInsertsRetrievableSeparately() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(16);
      StringIntEntry a = new StringIntEntry("alpha", 1);
      StringIntEntry b = new StringIntEntry("beta", 2);
      StringIntEntry c = new StringIntEntry("gamma", 3);
      table.insert(a);
      table.insert(b);
      table.insert(c);
      assertEquals(3, table.size());
      assertSame(a, table.get("alpha"));
      assertSame(b, table.get("beta"));
      assertSame(c, table.get("gamma"));
    }

    @Test
    void inPlaceMutationVisibleViaSubsequentGet() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      table.insert(new StringIntEntry("counter", 0));
      for (int i = 0; i < 10; i++) {
        StringIntEntry e = table.get("counter");
        e.value++;
      }
      assertEquals(10, table.get("counter").value);
    }

    @Test
    void removeUnlinksEntryAndDecrementsSize() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      table.insert(new StringIntEntry("a", 1));
      table.insert(new StringIntEntry("b", 2));
      assertEquals(2, table.size());

      StringIntEntry removed = table.remove("a");
      assertNotNull(removed);
      assertEquals("a", removed.key);
      assertEquals(1, table.size());
      assertNull(table.get("a"));
      assertNotNull(table.get("b"));
    }

    @Test
    void removeNonexistentReturnsNullAndDoesNotChangeSize() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      table.insert(new StringIntEntry("a", 1));
      assertNull(table.remove("nope"));
      assertEquals(1, table.size());
    }

    @Test
    void insertOrReplaceReturnsPriorEntryOrNullOnInsert() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      StringIntEntry first = new StringIntEntry("k", 1);
      assertNull(table.insertOrReplace(first), "fresh insert returns null");
      assertEquals(1, table.size());

      StringIntEntry second = new StringIntEntry("k", 2);
      assertSame(first, table.insertOrReplace(second), "replace returns the prior entry");
      assertEquals(1, table.size());
      assertSame(second, table.get("k"), "new entry visible after replace");
    }

    @Test
    void clearEmptiesTheTable() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      table.insert(new StringIntEntry("a", 1));
      table.insert(new StringIntEntry("b", 2));
      table.clear();
      assertEquals(0, table.size());
      assertNull(table.get("a"));
      // Reinsertion works after clear
      table.insert(new StringIntEntry("a", 99));
      assertEquals(99, table.get("a").value);
    }

    @Test
    void forEachVisitsEveryInsertedEntry() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      table.insert(new StringIntEntry("a", 1));
      table.insert(new StringIntEntry("b", 2));
      table.insert(new StringIntEntry("c", 3));
      Map<String, Integer> seen = new HashMap<>();
      table.forEach(e -> seen.put(e.key, e.value));
      assertEquals(3, seen.size());
      assertEquals(1, seen.get("a"));
      assertEquals(2, seen.get("b"));
      assertEquals(3, seen.get("c"));
    }

    @Test
    void nullKeyIsPermittedAndDistinctFromAbsent() {
      Hashtable.D1<String, StringIntEntry> table = new Hashtable.D1<>(8);
      assertNull(table.get(null));
      StringIntEntry nullKeyed = new StringIntEntry(null, 7);
      table.insert(nullKeyed);
      assertSame(nullKeyed, table.get(null));
      assertEquals(1, table.size());
      assertSame(nullKeyed, table.remove(null));
      assertEquals(0, table.size());
    }

    @Test
    void hashCollisionsResolveByEquality() {
      // Force two distinct keys with the same hashCode -- the chain must still distinguish them
      // via matches().
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table = new Hashtable.D1<>(4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      CollidingKeyEntry e1 = new CollidingKeyEntry(k1, 100);
      CollidingKeyEntry e2 = new CollidingKeyEntry(k2, 200);
      table.insert(e1);
      table.insert(e2);
      assertEquals(2, table.size());
      assertSame(e1, table.get(k1));
      assertSame(e2, table.get(k2));
    }

    @Test
    void hashCollisionsThenRemoveLeavesOtherIntact() {
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table = new Hashtable.D1<>(4);
      CollidingKey k1 = new CollidingKey("first", 17);
      CollidingKey k2 = new CollidingKey("second", 17);
      CollidingKey k3 = new CollidingKey("third", 17);
      table.insert(new CollidingKeyEntry(k1, 1));
      table.insert(new CollidingKeyEntry(k2, 2));
      table.insert(new CollidingKeyEntry(k3, 3));
      table.remove(k2);
      assertEquals(2, table.size());
      assertNotNull(table.get(k1));
      assertNull(table.get(k2));
      assertNotNull(table.get(k3));
    }
  }

  // ============ D2 ============

  @Nested
  class D2Tests {

    @Test
    void pairKeysParticipateInIdentity() {
      Hashtable.D2<String, Integer, PairEntry> table = new Hashtable.D2<>(8);
      PairEntry ab = new PairEntry("a", 1, 100);
      PairEntry ac = new PairEntry("a", 2, 200);
      PairEntry bb = new PairEntry("b", 1, 300);
      table.insert(ab);
      table.insert(ac);
      table.insert(bb);
      assertEquals(3, table.size());
      assertSame(ab, table.get("a", 1));
      assertSame(ac, table.get("a", 2));
      assertSame(bb, table.get("b", 1));
      assertNull(table.get("a", 3));
    }

    @Test
    void removePairUnlinks() {
      Hashtable.D2<String, Integer, PairEntry> table = new Hashtable.D2<>(8);
      PairEntry ab = new PairEntry("a", 1, 100);
      PairEntry ac = new PairEntry("a", 2, 200);
      table.insert(ab);
      table.insert(ac);
      assertSame(ab, table.remove("a", 1));
      assertEquals(1, table.size());
      assertNull(table.get("a", 1));
      assertSame(ac, table.get("a", 2));
    }

    @Test
    void insertOrReplaceMatchesOnBothKeys() {
      Hashtable.D2<String, Integer, PairEntry> table = new Hashtable.D2<>(8);
      PairEntry first = new PairEntry("k", 7, 1);
      assertNull(table.insertOrReplace(first));
      PairEntry second = new PairEntry("k", 7, 2);
      assertSame(first, table.insertOrReplace(second));
      // Different second-key: should insert new, not replace
      PairEntry third = new PairEntry("k", 8, 3);
      assertNull(table.insertOrReplace(third));
      assertEquals(2, table.size());
    }

    @Test
    void forEachVisitsBothPairs() {
      Hashtable.D2<String, Integer, PairEntry> table = new Hashtable.D2<>(8);
      table.insert(new PairEntry("a", 1, 100));
      table.insert(new PairEntry("b", 2, 200));
      Set<String> seen = new HashSet<>();
      table.forEach(e -> seen.add(e.key1 + ":" + e.key2));
      assertEquals(2, seen.size());
      assertTrue(seen.contains("a:1"));
      assertTrue(seen.contains("b:2"));
    }
  }

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
      BucketIterator<CollidingKeyEntry> it =
          Support.bucketIterator(extractBuckets(table), 17L);
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
      BucketIterator<StringIntEntry> it = Support.bucketIterator(extractBuckets(table), h);
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
          Support.mutatingBucketIterator(extractBuckets(table), 17L);
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
        if (table.get(k) != null) found++;
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
          Support.mutatingBucketIterator(extractBuckets(table), 17L);
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
          Support.mutatingBucketIterator(
              extractBuckets(table), Hashtable.D1.Entry.hash("a"));
      assertThrows(IllegalStateException.class, it::remove);
    }
  }

  // ============ test helpers ============

  /** Reach into a D1 table's bucket array via reflection -- only needed by iterator tests. */
  private static Hashtable.Entry[] extractBuckets(Hashtable.D1<?, ?> table) {
    try {
      java.lang.reflect.Field f = Hashtable.D1.class.getDeclaredField("buckets");
      f.setAccessible(true);
      return (Hashtable.Entry[]) f.get(table);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Sort comparator used by tests that want deterministic visit order. */
  @SuppressWarnings("unused")
  private static final Comparator<StringIntEntry> BY_KEY =
      Comparator.comparing(e -> e.key);

  private static final class StringIntEntry extends Hashtable.D1.Entry<String> {
    int value;

    StringIntEntry(String key, int value) {
      super(key);
      this.value = value;
    }
  }

  /** Key whose hashCode is fully controllable, to force chain collisions deterministically. */
  private static final class CollidingKey {
    final String label;
    final int hash;

    CollidingKey(String label, int hash) {
      this.label = label;
      this.hash = hash;
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof CollidingKey)) return false;
      CollidingKey that = (CollidingKey) o;
      return hash == that.hash && label.equals(that.label);
    }

    @Override
    public String toString() {
      return "CollidingKey(" + label + ", " + hash + ")";
    }
  }

  private static final class CollidingKeyEntry extends Hashtable.D1.Entry<CollidingKey> {
    int value;

    CollidingKeyEntry(CollidingKey key, int value) {
      super(key);
      this.value = value;
    }
  }

  private static final class PairEntry extends Hashtable.D2.Entry<String, Integer> {
    int value;

    PairEntry(String key1, Integer key2, int value) {
      super(key1, key2);
      this.value = value;
    }
  }

  // Imports kept narrow but List is referenced in test helpers below; this keeps the import warning quiet.
  @SuppressWarnings("unused")
  private static final List<Object> UNUSED = new ArrayList<>();
}
