package datadog.trace.util;

import static datadog.trace.util.HashtableTestEntries.CollidingKey;
import static datadog.trace.util.HashtableTestEntries.CollidingKeyEntry;
import static datadog.trace.util.HashtableTestEntries.StringIntEntry;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ObjIntConsumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Growth of the {@link Hashtable.D1} / {@link Hashtable.D2} bucket arrays, and the static resize.
 */
class HashtableGrowthTest {

  /**
   * capacityFor(100) is 256 buckets; the table starts at a quarter (64) and doubles at 49 and 97.
   */
  private static final int BOUND = 100;

  /** Growth is opt-in: an explicit initial capacity, here 0, starts at the quarter-size floor. */
  private static Hashtable.Sizing growable(int bound) {
    return Hashtable.Sizing.bounded(bound).initialCapacity(0);
  }

  private static Hashtable.D1<String, StringIntEntry> d1(int bound) {
    return Hashtable.D1.create(StringIntEntry.class, growable(bound));
  }

  private static Hashtable.D2<String, Integer, PairEntry> d2(int bound) {
    return Hashtable.D2.create(PairEntry.class, growable(bound));
  }

  /** A quarter of the full size, at least 16 buckets, never more than the full size. */
  private static int quarterStart(int full) {
    return Math.min(full, Math.max(16, full / 4));
  }

  private static String key(int i) {
    return "k" + i;
  }

  @Nested
  class D1Growth {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 5, 12, 13, 24, 25, 48, 49, 100, 1000})
    void startsAtAQuarterAndGrowsAtMostTwiceToCapacityFor(int bound) {
      Hashtable.D1<String, StringIntEntry> table = d1(bound);
      int full = Hashtable.capacityFor(bound);
      assertEquals(quarterStart(full), table.buckets.length);

      int growths = 0;
      for (int i = 0; i < bound; i++) {
        int before = table.buckets.length;
        assertTrue(table.insert(new StringIntEntry(key(i), i)));
        assertTrue(table.buckets.length <= full, "grew past capacityFor(bound)");
        if (table.buckets.length != before) {
          growths++;
        }
      }
      assertTrue(growths <= Hashtable.MAX_GROWTHS, "grew " + growths + " times");
      assertEquals(full, table.buckets.length);
      assertTrue(table.isFull());

      assertFalse(table.insert(new StringIntEntry("refused", -1)));
      assertEquals(full, table.buckets.length);
      assertEquals(bound, table.size());
    }

    @Test
    void doublesOncePastThreeQuartersFull() {
      Hashtable.D1<String, StringIntEntry> table = d1(BOUND);
      List<Integer> lengths = new ArrayList<>();
      int last = table.buckets.length;
      lengths.add(last);
      for (int i = 0; i < BOUND; i++) {
        table.insert(new StringIntEntry(key(i), i));
        if (table.buckets.length != last) {
          assertEquals(last * 2, table.buckets.length);
          last = table.buckets.length;
          lengths.add(i + 1); // the size at which it grew
          lengths.add(last);
        }
      }
      // length, size-at-growth, new length, ...
      assertEquals(asList(64, 49, 128, 97, 256), lengths, "growth points");
    }

    @Test
    void everyEntryIsFoundAfterGrowth() {
      Hashtable.D1<String, StringIntEntry> table = d1(BOUND);
      for (int i = 0; i < BOUND; i++) {
        table.insert(new StringIntEntry(key(i), i));
      }
      for (int i = 0; i < BOUND; i++) {
        StringIntEntry e = table.get(key(i));
        assertNotNull(e, key(i));
        assertEquals(i, e.value);
      }
      assertNull(table.get("absent"));
    }

    @Test
    void collidingKeysAreFoundAfterGrowth() {
      Hashtable.D1<CollidingKey, CollidingKeyEntry> table =
          Hashtable.D1.create(CollidingKeyEntry.class, growable(BOUND));
      List<CollidingKey> keys = new ArrayList<>();
      for (int i = 0; i < 60; i++) {
        // Multiples of 64 share bucket 0 at the starting length and split as the array doubles;
        // pairs with the same hash stay chained together at every length.
        keys.add(new CollidingKey("a" + i, (i / 2) * 64));
      }
      for (int i = 0; i < keys.size(); i++) {
        assertTrue(table.insert(new CollidingKeyEntry(keys.get(i), i)));
      }
      assertEquals(128, table.buckets.length);
      for (int i = 0; i < keys.size(); i++) {
        CollidingKeyEntry e = table.get(keys.get(i));
        assertNotNull(e, keys.get(i).toString());
        assertEquals(i, e.value);
      }
      assertNull(table.get(new CollidingKey("missing", 0)));
    }

    @Test
    void eachInsertPathTriggersGrowth() {
      List<ObjIntConsumer<Hashtable.D1<String, StringIntEntry>>> paths = new ArrayList<>();
      paths.add((t, i) -> assertTrue(t.insert(new StringIntEntry(key(i), i))));
      paths.add((t, i) -> assertTrue(t.tryInsertOrReplace(new StringIntEntry(key(i), i))));
      paths.add((t, i) ->
          assertTrue(t.tryGetOrCreate(key(i), k -> new StringIntEntry(k, i)).isPresent()));
      paths.add(
          (t, i) -> assertNotNull(t.tryGetOrCreateOrNull(key(i), k -> new StringIntEntry(k, i))));
      paths.add((t, i) ->
          assertTrue(
              t.tryGetOrCreateOrEvict(key(i), k -> new StringIntEntry(k, i), e -> true)
                  .isPresent()));
      paths.add((t, i) ->
          assertNotNull(
              t.tryGetOrCreateOrEvictOrNull(key(i), k -> new StringIntEntry(k, i), e -> true)));

      for (int p = 0; p < paths.size(); p++) {
        Hashtable.D1<String, StringIntEntry> table = d1(BOUND);
        for (int i = 0; i < 48; i++) {
          paths.get(p).accept(table, i);
        }
        assertEquals(64, table.buckets.length, "path " + p + " grew early");
        paths.get(p).accept(table, 48);
        assertEquals(128, table.buckets.length, "path " + p + " did not grow");
        for (int i = 49; i < BOUND; i++) {
          paths.get(p).accept(table, i);
        }
        assertEquals(256, table.buckets.length, "path " + p);
        assertEquals(BOUND, table.size(), "path " + p);
        for (int i = 0; i < BOUND; i++) {
          assertNotNull(table.get(key(i)), "path " + p + " lost " + key(i));
        }
      }
    }

    @Test
    void replacementDoesNotGrow() {
      Hashtable.D1<String, StringIntEntry> table = d1(BOUND);
      for (int i = 0; i < 48; i++) {
        table.insert(new StringIntEntry(key(i), i));
      }
      for (int i = 0; i < 48; i++) {
        assertTrue(table.tryInsertOrReplace(new StringIntEntry(key(i), i + 100)));
      }
      assertEquals(64, table.buckets.length);
      assertEquals(48, table.size());
      assertEquals(105, table.get(key(5)).value);
    }

    @Test
    void evictionAtTheBoundKeepsSizeExactAcrossGrowth() {
      Hashtable.D1<String, StringIntEntry> table = d1(BOUND);
      for (int i = 0; i < 3 * BOUND; i++) {
        StringIntEntry created =
            table.tryGetOrCreateOrEvictOrNull(key(i), k -> new StringIntEntry(k, 0), e -> true);
        assertNotNull(created);
        assertSame(created, table.get(key(i)));
        assertEquals(Math.min(i + 1, BOUND), table.size());
      }
      assertEquals(256, table.buckets.length);
      assertEquals(BOUND, countForEach(table));
    }

    @Test
    void evictionRefusesWhenNothingIsEvictable() {
      Hashtable.D1<String, StringIntEntry> table = d1(BOUND);
      for (int i = 0; i < BOUND; i++) {
        table.tryGetOrCreateOrEvict(key(i), k -> new StringIntEntry(k, 0), e -> false);
      }
      assertFalse(
          table
              .tryGetOrCreateOrEvict("extra", k -> new StringIntEntry(k, 0), e -> false)
              .isPresent());
      assertEquals(BOUND, table.size());
    }

    @Test
    void removeRemoveIfClearAndDrainAfterGrowth() {
      Hashtable.D1<String, StringIntEntry> table = d1(BOUND);
      for (int i = 0; i < 60; i++) {
        table.insert(new StringIntEntry(key(i), i));
      }
      assertEquals(128, table.buckets.length);

      for (int i = 0; i < 60; i += 3) {
        assertNotNull(table.remove(key(i)));
      }
      assertEquals(40, table.size());
      assertTrue(table.removeIf(e -> e.value % 3 == 1));
      assertEquals(20, table.size());
      for (int i = 0; i < 60; i++) {
        assertEquals(i % 3 == 2, table.get(key(i)) != null, key(i));
      }

      Set<String> drained = new HashSet<>();
      table.drain(e -> drained.add(e.key()));
      assertEquals(20, drained.size());
      assertEquals(0, table.size());
      assertEquals(0, countForEach(table));

      for (int i = 0; i < 60; i++) {
        table.insert(new StringIntEntry(key(i), i));
      }
      table.clear();
      assertEquals(0, table.size());
      assertNull(table.get(key(1)));
      // clear keeps the grown array; refilling to the bound still ends at the full-table size.
      for (int i = 0; i < BOUND; i++) {
        assertTrue(table.insert(new StringIntEntry(key(i), i)));
      }
      assertEquals(256, table.buckets.length);
    }

    @Test
    void forEachAndCursorSeeEveryEntryOnceAfterGrowth() {
      Hashtable.D1<String, StringIntEntry> table = d1(BOUND);
      for (int i = 0; i < 70; i++) {
        table.insert(new StringIntEntry(key(i), i));
      }
      Set<String> viaForEach = new HashSet<>();
      table.forEach(e -> assertTrue(viaForEach.add(e.key()), "seen twice: " + e.key()));
      assertEquals(70, viaForEach.size());

      Set<String> viaContextForEach = new HashSet<>();
      table.forEach(viaContextForEach, (set, e) -> assertTrue(set.add(e.key())));
      assertEquals(70, viaContextForEach.size());

      Set<String> viaCursor = new HashSet<>();
      Cursor<StringIntEntry> cursor = table.cursor();
      for (StringIntEntry e = cursor.next(); e != null; e = cursor.next()) {
        assertTrue(viaCursor.add(e.key()), "seen twice: " + e.key());
      }
      assertEquals(viaForEach, viaCursor);
    }

    private int countForEach(Hashtable.D1<String, StringIntEntry> table) {
      int[] count = {0};
      table.forEach(e -> count[0]++);
      return count[0];
    }
  }

  @Nested
  class D2Growth {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 7, 12, 13, 100, 1000})
    void startsAtAQuarterAndGrowsAtMostTwiceToCapacityFor(int bound) {
      Hashtable.D2<String, Integer, PairEntry> table = d2(bound);
      int full = Hashtable.capacityFor(bound);
      assertEquals(quarterStart(full), table.buckets.length);
      int growths = 0;
      for (int i = 0; i < bound; i++) {
        int before = table.buckets.length;
        assertTrue(table.insert(new PairEntry(key(i), i)));
        assertTrue(table.buckets.length <= full);
        if (table.buckets.length != before) {
          growths++;
        }
      }
      assertTrue(growths <= Hashtable.MAX_GROWTHS, "grew " + growths + " times");
      assertEquals(full, table.buckets.length);
      assertFalse(table.insert(new PairEntry("refused", -1)));
      for (int i = 0; i < bound; i++) {
        assertNotNull(table.get(key(i), i));
      }
    }

    @Test
    void eachInsertPathTriggersGrowth() {
      List<ObjIntConsumer<Hashtable.D2<String, Integer, PairEntry>>> paths = new ArrayList<>();
      paths.add((t, i) -> assertTrue(t.insert(new PairEntry(key(i), i))));
      paths.add((t, i) -> assertTrue(t.tryInsertOrReplace(new PairEntry(key(i), i))));
      paths.add((t, i) -> assertTrue(t.tryGetOrCreate(key(i), i, PairEntry::new).isPresent()));
      paths.add((t, i) -> assertNotNull(t.tryGetOrCreateOrNull(key(i), i, PairEntry::new)));
      paths.add((t, i) ->
          assertTrue(t.tryGetOrCreateOrEvict(key(i), i, PairEntry::new, e -> true).isPresent()));
      paths.add((t, i) ->
          assertNotNull(t.tryGetOrCreateOrEvictOrNull(key(i), i, PairEntry::new, e -> true)));

      for (int p = 0; p < paths.size(); p++) {
        Hashtable.D2<String, Integer, PairEntry> table = d2(BOUND);
        for (int i = 0; i < 48; i++) {
          paths.get(p).accept(table, i);
        }
        assertEquals(64, table.buckets.length, "path " + p + " grew early");
        paths.get(p).accept(table, 48);
        assertEquals(128, table.buckets.length, "path " + p + " did not grow");
        for (int i = 49; i < BOUND; i++) {
          paths.get(p).accept(table, i);
        }
        assertEquals(256, table.buckets.length, "path " + p);
        assertEquals(BOUND, table.size(), "path " + p);
        for (int i = 0; i < BOUND; i++) {
          assertNotNull(table.get(key(i), i), "path " + p + " lost " + key(i));
        }
      }
    }

    @Test
    void evictionRemoveAndIterationAfterGrowth() {
      Hashtable.D2<String, Integer, PairEntry> table = d2(BOUND);
      for (int i = 0; i < 2 * BOUND; i++) {
        assertNotNull(table.tryGetOrCreateOrEvictOrNull(key(i), i, PairEntry::new, e -> true));
        assertEquals(Math.min(i + 1, BOUND), table.size());
      }
      assertEquals(256, table.buckets.length);

      Set<String> seen = new HashSet<>();
      Cursor<PairEntry> cursor = table.cursor();
      for (PairEntry e = cursor.next(); e != null; e = cursor.next()) {
        assertTrue(seen.add(e.key1()));
      }
      assertEquals(BOUND, seen.size());

      String any = seen.iterator().next();
      assertNotNull(table.remove(any, Integer.parseInt(any.substring(1))));
      assertEquals(BOUND - 1, table.size());
      table.clear();
      assertEquals(0, table.size());
    }
  }

  @Nested
  class ExplicitSizing {

    @Test
    void defaultSizingIsFixedAndNeverGrows() {
      int full = Hashtable.capacityFor(BOUND);
      Hashtable.D1<String, StringIntEntry> viaCreate =
          Hashtable.D1.create(StringIntEntry.class, Hashtable.Sizing.bounded(BOUND));
      Hashtable.D1<String, StringIntEntry> viaCreateBounded =
          Hashtable.D1.createBounded(StringIntEntry.class, BOUND);
      Hashtable.D2<String, Integer, PairEntry> viaCreate2 =
          Hashtable.D2.create(PairEntry.class, Hashtable.Sizing.bounded(BOUND));
      Hashtable.D2<String, Integer, PairEntry> viaCreateBounded2 =
          Hashtable.D2.createBounded(PairEntry.class, BOUND);
      assertEquals(full, viaCreate.buckets.length);
      assertEquals(full, viaCreateBounded.buckets.length);
      assertEquals(full, viaCreate2.buckets.length);
      assertEquals(full, viaCreateBounded2.buckets.length);

      Hashtable.Entry[] initial = viaCreateBounded.buckets;
      Hashtable.Entry[] initial2 = viaCreateBounded2.buckets;
      for (int i = 0; i < BOUND; i++) {
        assertTrue(viaCreateBounded.insert(new StringIntEntry(key(i), i)));
        assertTrue(viaCreateBounded2.insert(new PairEntry(key(i), i)));
      }
      assertSame(initial, viaCreateBounded.buckets);
      assertSame(initial2, viaCreateBounded2.buckets);
    }

    @Test
    void explicitInitialCapacityAboveTheFloorSetsStartThenGrowsToTheCap() {
      // capacityFor(1000) is 2048 buckets, so the floor is 512; 700 entries need 1024.
      Hashtable.Sizing sizing = Hashtable.Sizing.bounded(1000).initialCapacity(700);
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.create(StringIntEntry.class, sizing);
      assertEquals(1024, Hashtable.capacityFor(700));
      assertEquals(Hashtable.capacityFor(700), table.buckets.length);
      for (int i = 0; i < 1000; i++) {
        assertTrue(table.insert(new StringIntEntry(key(i), i)));
      }
      assertEquals(Hashtable.capacityFor(1000), table.buckets.length);

      Hashtable.D2<String, Integer, PairEntry> table2 =
          Hashtable.D2.create(PairEntry.class, sizing);
      assertEquals(Hashtable.capacityFor(700), table2.buckets.length);
      for (int i = 0; i < 1000; i++) {
        assertTrue(table2.insert(new PairEntry(key(i), i)));
      }
      assertEquals(Hashtable.capacityFor(1000), table2.buckets.length);
    }

    @Test
    void initialCapacityBelowTheFloorStartsAtAQuarter() {
      assertEquals(
          512,
          Hashtable.D1.create(
                  StringIntEntry.class, Hashtable.Sizing.bounded(1000).initialCapacity(100))
              .buckets
              .length);
      assertEquals(
          512,
          Hashtable.D2.create(PairEntry.class, Hashtable.Sizing.bounded(1000).initialCapacity(1))
              .buckets
              .length);
    }

    @Test
    void zeroInitialCapacityStartsAtTheFloor() {
      // capacityFor(50) is 128 buckets, so the floor is 32.
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.create(
              StringIntEntry.class, Hashtable.Sizing.bounded(50).initialCapacity(0));
      assertEquals(32, table.buckets.length);
      for (int i = 0; i < 50; i++) {
        assertTrue(table.insert(new StringIntEntry(key(i), i)));
      }
      assertEquals(Hashtable.capacityFor(50), table.buckets.length);
      for (int i = 0; i < 50; i++) {
        assertNotNull(table.get(key(i)));
      }
    }

    @Test
    void initialCapacityAtTheBoundStartsFullSizeAndNeverGrows() {
      Hashtable.Sizing sizing = Hashtable.Sizing.bounded(BOUND).initialCapacity(BOUND * 10);
      Hashtable.D1<String, StringIntEntry> table =
          Hashtable.D1.create(StringIntEntry.class, sizing);
      Hashtable.Entry[] initial = table.buckets;
      assertEquals(Hashtable.capacityFor(BOUND), initial.length);
      for (int i = 0; i < BOUND; i++) {
        table.insert(new StringIntEntry(key(i), i));
      }
      assertSame(initial, table.buckets);
    }
  }

  @Nested
  class StaticResize {

    @Test
    void resizeRelinksEveryEntryIntoADoubledArray() {
      Hashtable.Entry[] buckets = Hashtable.create(4);
      List<CollidingKeyEntry> entries = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        // Hashes 0, 4, 8, ... share a bucket at length 4; equal hashes stay chained.
        CollidingKeyEntry e = new CollidingKeyEntry(new CollidingKey("c" + i, (i / 2) * 4), i);
        entries.add(e);
        Hashtable.insertHeadEntryFor(buckets, e.keyHash, e);
      }

      Hashtable.Entry[] grown = Hashtable.resize(buckets);
      assertEquals(8, grown.length);
      assertEquals(Hashtable.Entry.class, grown.getClass().getComponentType());

      int[] count = {0};
      Hashtable.forEach(grown, e -> count[0]++);
      assertEquals(entries.size(), count[0]);
      for (CollidingKeyEntry expected : entries) {
        CollidingKeyEntry found = null;
        for (CollidingKeyEntry e = Hashtable.bucketFor(grown, expected.keyHash);
            e != null;
            e = e.next()) {
          if (e.matches(expected.key())) {
            found = e;
          }
        }
        assertSame(expected, found);
      }
    }

    @Test
    void resizeKeepsATypedComponentType() {
      StringIntEntry[] buckets = Hashtable.create(StringIntEntry.class, 2);
      StringIntEntry a = new StringIntEntry("a", 1);
      Hashtable.insertHeadEntryFor(buckets, a.keyHash, a);
      StringIntEntry[] grown = Hashtable.resize(buckets);
      assertEquals(4, grown.length);
      assertSame(a, Hashtable.bucketFor(grown, a.keyHash));
    }

    @Test
    void resizeWithSizeManagerRestartsEvictionScanAndKeepsCount() {
      Hashtable.SizeManager size = new Hashtable.SizeManager(6);
      Hashtable.Entry[] buckets = Hashtable.create(4);
      for (int i = 0; i < 6; i++) {
        StringIntEntry e = new StringIntEntry(key(i), i);
        assertTrue(Hashtable.insertHeadEntryFor(size, buckets, e.keyHash, e));
      }
      // Leave the scan saved mid-table in the old array.
      StringIntEntry evicted = size.evictOne(buckets, e -> ((StringIntEntry) e).value >= 3);
      assertNotNull(evicted);
      assertEquals(5, size.estimateSize());

      Hashtable.Entry[] grown = Hashtable.resize(size, buckets);
      assertEquals(5, size.estimateSize());

      Set<String> remaining = new HashSet<>();
      Hashtable.<StringIntEntry>forEach(grown, e -> remaining.add(e.key()));
      assertEquals(5, remaining.size());
      assertFalse(remaining.contains(evicted.key()));

      // Every remaining evictable entry is still found in the new array.
      int evictions = 0;
      while (size.evictOne(grown, e -> ((StringIntEntry) e).value >= 3) != null) {
        evictions++;
      }
      assertEquals(2, evictions);
      assertEquals(3, size.estimateSize());
      int[] count = {0};
      Hashtable.forEach(grown, e -> count[0]++);
      assertEquals(3, count[0]);
    }

    @Test
    void grownLengthDoublesAndRefusesPastTheMaximum() {
      assertEquals(2, Hashtable.grownLength(1));
      assertEquals(Hashtable.MAX_BUCKETS, Hashtable.grownLength(Hashtable.MAX_BUCKETS >> 1));
      assertThrows(IllegalStateException.class, () -> Hashtable.grownLength(Hashtable.MAX_BUCKETS));
    }
  }

  private static final class PairEntry extends Hashtable.D2.Entry<String, Integer> {
    PairEntry(String key1, Integer key2) {
      super(key1, key2);
    }
  }
}
