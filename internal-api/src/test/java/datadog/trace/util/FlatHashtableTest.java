package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FlatHashtableTest {

  /**
   * Self-contained entry: carries its own key + cached spread hash (the FlatHashtable contract).
   */
  static final class Entry {
    final String key;
    final int hash;

    Entry(String key, int hash) {
      this.key = key;
      this.hash = hash;
    }
  }

  /** Counts create() calls so tests can prove getOrCreate mints exactly once per key. */
  static final class CountingHelper extends FlatHashtable.StringHelper<Entry> {
    int creates;

    @Override
    public boolean matches(String key, Entry value) {
      return this.hash(key) == value.hash && key.equals(value.key);
    }

    @Override
    public Entry create(String key) {
      this.creates++;
      return new Entry(key, this.hash(key));
    }
  }

  /** A helper whose keys all collide to slot 0, to exercise linear probing + fill-to-full. */
  static final class CollidingHelper extends FlatHashtable.Helper<String, Entry> {
    @Override
    public int hash(String key) {
      return 0;
    }

    @Override
    public boolean matches(String key, Entry value) {
      return key.equals(value.key);
    }

    @Override
    public Entry create(String key) {
      return new Entry(key, 0);
    }
  }

  @Test
  void capacityForIsPowerOfTwoAtLeastTwiceLimit() {
    for (int limit = 1; limit <= 1024; limit++) {
      int cap = FlatHashtable.capacityFor(limit);
      assertTrue(cap >= 2 * limit, "cap " + cap + " >= 2*" + limit);
      assertEquals(0, cap & (cap - 1), "cap " + cap + " is a power of two");
    }
    assertEquals(2, FlatHashtable.capacityFor(1));
    assertEquals(4, FlatHashtable.capacityFor(2));
    assertEquals(8, FlatHashtable.capacityFor(3));
    assertEquals(8, FlatHashtable.capacityFor(4));
    assertEquals(1024, FlatHashtable.capacityFor(512));
  }

  @Test
  void capacityForRejectsNonPositive() {
    assertThrows(IllegalArgumentException.class, () -> FlatHashtable.capacityFor(0));
    assertThrows(IllegalArgumentException.class, () -> FlatHashtable.capacityFor(-1));
  }

  @Test
  void createAllocatesTypedArrayOfCapacity() {
    Entry[] table = FlatHashtable.create(Entry.class, 512);
    assertEquals(1024, table.length);
    assertEquals(Entry.class, table.getClass().getComponentType());
  }

  @Test
  void getReturnsNullOnEmptyTable() {
    Entry[] table = FlatHashtable.create(Entry.class, 8);
    assertNull(FlatHashtable.get(table, "absent", new CountingHelper()));
  }

  @Test
  void getOrCreateMintsOnceThenReturnsSameInstance() {
    Entry[] table = FlatHashtable.create(Entry.class, 8);
    CountingHelper helper = new CountingHelper();

    Entry first = FlatHashtable.getOrCreate(table, "op", helper);
    assertNotNull(first);
    assertEquals(1, helper.creates);

    Entry again = FlatHashtable.getOrCreate(table, "op", helper);
    assertSame(first, again, "second getOrCreate returns the existing entry");
    assertEquals(1, helper.creates, "no re-mint on hit");

    assertSame(first, FlatHashtable.get(table, "op", helper), "get sees the inserted entry");
  }

  @Test
  void storesManyDistinctKeysWithinBudget() {
    int limit = 200;
    Entry[] table = FlatHashtable.create(Entry.class, limit);
    CountingHelper helper = new CountingHelper();

    Set<Entry> seen = new HashSet<>();
    for (int i = 0; i < limit; i++) {
      Entry e = FlatHashtable.getOrCreate(table, "op-" + i, helper);
      assertNotNull(e);
      seen.add(e);
    }
    assertEquals(limit, seen.size());
    assertEquals(limit, helper.creates);

    // All still retrievable (probing across collisions works).
    for (int i = 0; i < limit; i++) {
      assertNotNull(FlatHashtable.get(table, "op-" + i, helper));
    }
  }

  @Test
  void getOrCreateReturnsNullWhenPhysicallyFull() {
    // capacityFor(1) == 2 slots; all keys collide to slot 0 so 2 fills the table.
    Entry[] table = FlatHashtable.create(Entry.class, 1);
    assertEquals(2, table.length);
    CollidingHelper helper = new CollidingHelper();

    assertNotNull(FlatHashtable.getOrCreate(table, "a", helper));
    assertNotNull(FlatHashtable.getOrCreate(table, "b", helper));
    // Table is now full; a third distinct key has no empty slot.
    assertNull(FlatHashtable.getOrCreate(table, "c", helper));
    // But existing keys are still found via the wrapped probe.
    assertNotNull(FlatHashtable.get(table, "a", helper));
    assertNotNull(FlatHashtable.get(table, "b", helper));
    assertNull(FlatHashtable.get(table, "c", helper));
  }

  @Test
  void stringHelperHashIsSpreadAndStable() {
    CountingHelper helper = new CountingHelper();
    int h = helper.hash("component");
    assertEquals(h, helper.hash("component"), "hash is deterministic");
    int raw = "component".hashCode();
    assertEquals(raw ^ (raw >>> 16), h, "hash is the spread of String.hashCode()");
  }
}
