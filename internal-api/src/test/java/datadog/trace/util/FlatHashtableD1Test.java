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

class FlatHashtableD1Test {

  static final class StringIntEntry extends FlatHashtable.D1.Entry<String> {
    int value;

    StringIntEntry(String key, int value) {
      super(key);
      this.value = value;
    }
  }

  /** Key whose hashCode is fully controllable, to force probe collisions deterministically. */
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

  static final class CollidingKeyEntry extends FlatHashtable.D1.Entry<CollidingKey> {
    int value;

    CollidingKeyEntry(CollidingKey key, int value) {
      super(key);
      this.value = value;
    }
  }

  private static FlatHashtable.D1<String, StringIntEntry> growable(int initialCapacity) {
    return FlatHashtable.D1.createGrowable(StringIntEntry.class, initialCapacity);
  }

  private static FlatHashtable.D1<String, StringIntEntry> fixed(int maxCapacity) {
    return FlatHashtable.D1.createFixed(StringIntEntry.class, maxCapacity);
  }

  @Test
  void emptyTableLookupReturnsNull() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(8);
    assertNull(table.get("missing"));
    assertEquals(0, table.size());
  }

  @Test
  void insertedEntryIsRetrievable() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(8);
    StringIntEntry e = new StringIntEntry("foo", 1);
    assertTrue(table.insert(e));
    assertEquals(1, table.size());
    assertSame(e, table.get("foo"));
  }

  @Test
  void multipleInsertsRetrievableSeparately() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(16);
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
  void keyAccessorExposesConstructionKey() {
    StringIntEntry e = new StringIntEntry("foo", 1);
    assertEquals("foo", e.key());
  }

  @Test
  void inPlaceMutationVisibleViaSubsequentGet() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(8);
    table.insert(new StringIntEntry("counter", 0));
    for (int i = 0; i < 10; i++) {
      StringIntEntry e = table.get("counter");
      e.value++;
    }
    assertEquals(10, table.get("counter").value);
  }

  @Test
  void clearEmptiesTheTable() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(8);
    table.insert(new StringIntEntry("a", 1));
    table.insert(new StringIntEntry("b", 2));
    table.clear();
    assertEquals(0, table.size());
    assertNull(table.get("a"));
    // Reinsertion works after clear.
    table.insert(new StringIntEntry("a", 99));
    assertEquals(99, table.get("a").value);
  }

  @Test
  void forEachVisitsEveryInsertedEntry() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(8);
    table.insert(new StringIntEntry("a", 1));
    table.insert(new StringIntEntry("b", 2));
    table.insert(new StringIntEntry("c", 3));
    Map<String, Integer> seen = new HashMap<>();
    table.forEach(e -> seen.put(e.key(), e.value));
    assertEquals(3, seen.size());
    assertEquals(1, seen.get("a"));
    assertEquals(2, seen.get("b"));
    assertEquals(3, seen.get("c"));
  }

  @Test
  void forEachWithContextPassesContextToConsumer() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(8);
    table.insert(new StringIntEntry("a", 10));
    table.insert(new StringIntEntry("b", 20));
    Map<String, Integer> seen = new HashMap<>();
    table.forEach(seen, (ctx, e) -> ctx.put(e.key(), e.value));
    assertEquals(2, seen.size());
    assertEquals(10, seen.get("a"));
    assertEquals(20, seen.get("b"));
  }

  @Test
  void nullKeyIsPermittedAndDistinctFromAbsent() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(8);
    assertNull(table.get(null));
    StringIntEntry nullKeyed = new StringIntEntry(null, 7);
    table.insert(nullKeyed);
    assertSame(nullKeyed, table.get(null));
    assertEquals(1, table.size());
    // A real key that hashes to 0 must not collide with the null-key sentinel.
    StringIntEntry zeroHashed = new StringIntEntry("", 0);
    assertEquals(0, "".hashCode());
    table.insert(zeroHashed);
    assertSame(zeroHashed, table.get(""));
    assertSame(nullKeyed, table.get(null));
  }

  @Test
  void hashCollisionsResolveByEquality() {
    // Two distinct keys with the same hashCode: the probe must distinguish them via matches().
    FlatHashtable.D1<CollidingKey, CollidingKeyEntry> table =
        FlatHashtable.D1.createGrowable(CollidingKeyEntry.class, 4);
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
  void getOrCreateOnMissBuildsEntryViaCreator() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(8);
    int[] createCount = {0};
    StringIntEntry created =
        table.tryGetOrCreate(
            "foo",
            k -> {
              createCount[0]++;
              return new StringIntEntry(k, 42);
            });
    assertNotNull(created);
    assertEquals("foo", created.key());
    assertEquals(42, created.value);
    assertEquals(1, table.size());
    assertEquals(1, createCount[0]);
    assertSame(created, table.get("foo"));
  }

  @Test
  void getOrCreateOnHitSkipsCreator() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(8);
    StringIntEntry seeded = new StringIntEntry("foo", 1);
    table.insert(seeded);
    int[] createCount = {0};
    StringIntEntry got =
        table.tryGetOrCreate(
            "foo",
            k -> {
              createCount[0]++;
              return new StringIntEntry(k, 999);
            });
    assertSame(seeded, got);
    assertEquals(1, table.size());
    assertEquals(0, createCount[0]);
  }

  @Test
  void growableGrowsPastInitialCapacity() {
    // initialCapacity 1 => a tiny 2-slot table; inserting far past it must trigger growth.
    FlatHashtable.D1<String, StringIntEntry> table = growable(1);
    for (int i = 0; i < 50; i++) {
      assertTrue(table.insert(new StringIntEntry("k" + i, i)));
    }
    assertEquals(50, table.size());
    for (int i = 0; i < 50; i++) {
      assertEquals(i, table.get("k" + i).value);
    }
  }

  @Test
  void growableGetOrCreateNeverReturnsNull() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(1);
    for (int i = 0; i < 50; i++) {
      StringIntEntry e = table.tryGetOrCreate("k" + i, k -> new StringIntEntry(k, 0));
      assertNotNull(e);
    }
    assertEquals(50, table.size());
  }

  @Test
  void fixedGetOrCreateCapsWhenFull() {
    FlatHashtable.D1<String, StringIntEntry> table = fixed(2);
    assertNotNull(table.tryGetOrCreate("a", k -> new StringIntEntry(k, 1)));
    assertNotNull(table.tryGetOrCreate("b", k -> new StringIntEntry(k, 2)));
    assertEquals(2, table.size());
    // At capacity, a new key can't be created -> null (caller's overflow default).
    assertNull(table.tryGetOrCreate("c", k -> new StringIntEntry(k, 3)));
    assertEquals(2, table.size());
    // ...but an existing key still resolves even at capacity (cap blocks creation, not lookup).
    StringIntEntry a = table.get("a");
    assertSame(a, table.tryGetOrCreate("a", k -> new StringIntEntry(k, 99)));
  }

  @Test
  void fixedGetOrCreateAsMaybeIsAbsentWhenFullButStillReturnsHits() {
    FlatHashtable.D1<String, StringIntEntry> table = fixed(2);
    table.tryGetOrCreate("a", k -> new StringIntEntry(k, 1));
    table.tryGetOrCreate("b", k -> new StringIntEntry(k, 2));

    assertFalse(table.tryGetOrCreateAsMaybe("c", k -> new StringIntEntry(k, 3)).isPresent());

    Maybe<StringIntEntry> hit = table.tryGetOrCreateAsMaybe("a", k -> new StringIntEntry(k, 99));
    assertEquals(1, hit.getOrNull().value, "existing entry is still returned even at capacity");
  }

  @Test
  void growableGetOrCreateAsMaybeIsAlwaysPresent() {
    FlatHashtable.D1<String, StringIntEntry> table = growable(1);
    for (int i = 0; i < 50; i++) {
      assertTrue(table.tryGetOrCreateAsMaybe("k" + i, k -> new StringIntEntry(k, 0)).isPresent());
    }
    assertEquals(50, table.size());
  }

  @Test
  void fixedInsertReturnsFalseWhenFull() {
    FlatHashtable.D1<String, StringIntEntry> table = fixed(2);
    assertTrue(table.insert(new StringIntEntry("a", 1)));
    assertTrue(table.insert(new StringIntEntry("b", 2)));
    assertFalse(table.insert(new StringIntEntry("c", 3)));
    assertEquals(2, table.size());
  }
}
