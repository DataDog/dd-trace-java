package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlatHashtableD2Test {

  static final class PairEntry extends FlatHashtable.D2.Entry<String, Integer> {
    int value;

    PairEntry(String key1, Integer key2, int value) {
      super(key1, key2);
      this.value = value;
    }
  }

  /** A key whose hash is fixed at construction so composite-hash collisions can be forced. */
  static final class CollidingKey {
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
      if (!(o instanceof CollidingKey)) {
        return false;
      }
      CollidingKey that = (CollidingKey) o;
      return hash == that.hash && label.equals(that.label);
    }
  }

  static final class CollidingPairEntry extends FlatHashtable.D2.Entry<CollidingKey, CollidingKey> {
    int value;

    CollidingPairEntry(CollidingKey key1, CollidingKey key2, int value) {
      super(key1, key2);
      this.value = value;
    }
  }

  private static FlatHashtable.D2<String, Integer, PairEntry> growable(int initialCapacity) {
    return FlatHashtable.D2.createGrowable(PairEntry.class, initialCapacity);
  }

  private static FlatHashtable.D2<String, Integer, PairEntry> fixed(int maxCapacity) {
    return FlatHashtable.D2.createFixed(PairEntry.class, maxCapacity);
  }

  @Test
  void emptyTableLookupReturnsNull() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(8);
    assertNull(table.get("missing", 1));
    assertEquals(0, table.size());
  }

  @Test
  void insertedEntryIsRetrievableByBothParts() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(8);
    PairEntry e = new PairEntry("a", 1, 100);
    assertTrue(table.insert(e));
    assertEquals(1, table.size());
    assertSame(e, table.get("a", 1));
  }

  @Test
  void distinctCompositeKeysStaySeparate() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(16);
    PairEntry a1 = new PairEntry("a", 1, 10);
    PairEntry a2 = new PairEntry("a", 2, 20); // same key1, different key2
    PairEntry b1 = new PairEntry("b", 1, 30); // different key1, same key2
    table.insert(a1);
    table.insert(a2);
    table.insert(b1);
    assertEquals(3, table.size());
    assertSame(a1, table.get("a", 1));
    assertSame(a2, table.get("a", 2));
    assertSame(b1, table.get("b", 1));
    // A composite that was never inserted is absent.
    assertNull(table.get("b", 2));
  }

  @Test
  void keyAccessorsExposeConstructionKeys() {
    PairEntry e = new PairEntry("a", 1, 100);
    assertEquals("a", e.key1());
    assertEquals(1, e.key2());
  }

  @Test
  void inPlaceMutationVisibleViaSubsequentGet() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(8);
    table.insert(new PairEntry("counter", 7, 0));
    for (int i = 0; i < 10; i++) {
      PairEntry e = table.get("counter", 7);
      e.value++;
    }
    assertEquals(10, table.get("counter", 7).value);
  }

  @Test
  void clearEmptiesTheTable() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(8);
    table.insert(new PairEntry("a", 1, 1));
    table.insert(new PairEntry("b", 2, 2));
    table.clear();
    assertEquals(0, table.size());
    assertNull(table.get("a", 1));
  }

  @Test
  void forEachVisitsEveryInsertedEntry() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(8);
    table.insert(new PairEntry("a", 1, 1));
    table.insert(new PairEntry("b", 2, 2));
    table.insert(new PairEntry("c", 3, 3));
    Map<String, Integer> seen = new HashMap<>();
    table.forEach(e -> seen.put(e.key1() + e.key2(), e.value));
    assertEquals(3, seen.size());
    assertEquals(1, seen.get("a1"));
    assertEquals(2, seen.get("b2"));
    assertEquals(3, seen.get("c3"));
  }

  @Test
  void forEachWithContextPassesContextToConsumer() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(8);
    table.insert(new PairEntry("a", 1, 10));
    table.insert(new PairEntry("b", 2, 20));
    Map<String, Integer> seen = new HashMap<>();
    table.forEach(seen, (ctx, e) -> ctx.put(e.key1() + e.key2(), e.value));
    assertEquals(2, seen.size());
    assertEquals(10, seen.get("a1"));
    assertEquals(20, seen.get("b2"));
  }

  @Test
  void nullKeyPartsArePermitted() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(8);
    PairEntry bothNull = new PairEntry(null, null, 1);
    table.insert(bothNull);
    assertSame(bothNull, table.get(null, null));
    assertNull(table.get("a", null));
    assertNull(table.get(null, 1));
    assertEquals(1, table.size());
  }

  @Test
  void getOrCreateOnMissBuildsEntryViaCreator() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(8);
    int[] createCount = {0};
    PairEntry created =
        table.tryGetOrCreate(
            "foo",
            1,
            (k1, k2) -> {
              createCount[0]++;
              return new PairEntry(k1, k2, 42);
            });
    assertNotNull(created);
    assertEquals("foo", created.key1());
    assertEquals(1, created.key2());
    assertEquals(42, created.value);
    assertEquals(1, table.size());
    assertEquals(1, createCount[0]);
    assertSame(created, table.get("foo", 1));
  }

  @Test
  void getOrCreateOnHitSkipsCreator() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(8);
    PairEntry seeded = new PairEntry("foo", 1, 1);
    table.insert(seeded);
    int[] createCount = {0};
    PairEntry got =
        table.tryGetOrCreate(
            "foo",
            1,
            (k1, k2) -> {
              createCount[0]++;
              return new PairEntry(k1, k2, 999);
            });
    assertSame(seeded, got);
    assertEquals(1, table.size());
    assertEquals(0, createCount[0]);
  }

  @Test
  void growableGrowsPastInitialCapacity() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(1);
    for (int i = 0; i < 50; i++) {
      assertTrue(table.insert(new PairEntry("k", i, i)));
    }
    assertEquals(50, table.size());
    for (int i = 0; i < 50; i++) {
      assertEquals(i, table.get("k", i).value);
    }
  }

  @Test
  void fixedGetOrCreateCapsWhenFull() {
    FlatHashtable.D2<String, Integer, PairEntry> table = fixed(2);
    assertNotNull(table.tryGetOrCreate("a", 1, (k1, k2) -> new PairEntry(k1, k2, 1)));
    assertNotNull(table.tryGetOrCreate("b", 2, (k1, k2) -> new PairEntry(k1, k2, 2)));
    assertEquals(2, table.size());
    assertNull(table.tryGetOrCreate("c", 3, (k1, k2) -> new PairEntry(k1, k2, 3)));
    assertEquals(2, table.size());
    PairEntry a = table.get("a", 1);
    assertSame(a, table.tryGetOrCreate("a", 1, (k1, k2) -> new PairEntry(k1, k2, 99)));
  }

  @Test
  void fixedInsertReturnsFalseWhenFull() {
    FlatHashtable.D2<String, Integer, PairEntry> table = fixed(2);
    assertTrue(table.insert(new PairEntry("a", 1, 1)));
    assertTrue(table.insert(new PairEntry("b", 2, 2)));
    assertFalse(table.insert(new PairEntry("c", 3, 3)));
    assertEquals(2, table.size());
  }

  @Test
  void hashCollisionsResolveByKeyEquality() {
    FlatHashtable.D2<CollidingKey, CollidingKey, CollidingPairEntry> table =
        FlatHashtable.D2.createGrowable(CollidingPairEntry.class, 8);
    CollidingKey key1 = new CollidingKey("a", 7);
    CollidingKey key2 = new CollidingKey("x", 3);
    CollidingPairEntry e = new CollidingPairEntry(key1, key2, 100);
    table.insert(e);

    // Same combined hash (31*7 + 3) as the stored entry, but key1 differs by equals: the entry's
    // matches() must reject on key1, so the lookup misses rather than returning the wrong entry.
    assertNull(table.get(new CollidingKey("b", 7), key2));
    // Same combined hash again, key1 equal but key2 differs by equals: matches() must reject on
    // key2.
    assertNull(table.get(key1, new CollidingKey("y", 3)));
    // The genuine key still resolves.
    assertSame(e, table.get(key1, key2));
  }

  @Test
  void growableGetOrCreateGrowsPastInitialCapacity() {
    FlatHashtable.D2<String, Integer, PairEntry> table = growable(1);
    for (int i = 0; i < 50; i++) {
      PairEntry e = table.tryGetOrCreate("k", i, (k1, k2) -> new PairEntry(k1, k2, k2));
      assertNotNull(e);
    }
    assertEquals(50, table.size());
    for (int i = 0; i < 50; i++) {
      assertEquals(i, table.get("k", i).value);
    }
  }
}
