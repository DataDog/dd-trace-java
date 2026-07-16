package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlatHashtableTest {

  /** Self-contained entry: carries its own key (the identity FlatHashtable relies on). */
  static final class Entry {
    final String key;

    Entry(String key) {
      this.key = key;
    }
  }

  /** Stateless concrete helper, held as a concrete-typed static final singleton (the JIT idiom). */
  static final class EntryHelper extends FlatHashtable.StringHelper<Entry> {
    @Override
    public boolean matches(String key, Entry value) {
      return key.equals(value.key);
    }

    @Override
    public Entry create(String key) {
      return new Entry(key);
    }
  }

  private static final EntryHelper HELPER = new EntryHelper();

  @Test
  void capacityFor_roundsToPowerOfTwoAtLeastTwiceLimit() {
    assertEquals(2, FlatHashtable.capacityFor(1));
    assertEquals(8, FlatHashtable.capacityFor(4));
    assertEquals(16, FlatHashtable.capacityFor(6)); // 6*2-1=11 -> 8 -> 16
  }

  @Test
  void capacityFor_rejectsNonPositive() {
    assertThrows(IllegalArgumentException.class, () -> FlatHashtable.capacityFor(0));
    assertThrows(IllegalArgumentException.class, () -> FlatHashtable.capacityFor(-1));
  }

  @Test
  void create_allocatesTypedTableOfCapacity() {
    Entry[] table = FlatHashtable.create(Entry.class, 4);
    assertEquals(8, table.length);
    assertEquals(Entry.class, table.getClass().getComponentType());
  }

  @Test
  void getOrCreate_insertsOnceAndReturnsTheExistingEntry() {
    Entry[] table = FlatHashtable.create(Entry.class, 8);
    Entry first = FlatHashtable.getOrCreate(table, "a", HELPER);
    assertEquals("a", first.key);
    // A second call must return the SAME instance, not mint a new one.
    assertSame(first, FlatHashtable.getOrCreate(table, "a", HELPER));
    assertSame(first, FlatHashtable.get(table, "a", HELPER));
  }

  @Test
  void get_returnsNullForAbsentKey() {
    Entry[] table = FlatHashtable.create(Entry.class, 8);
    assertNull(FlatHashtable.get(table, "missing", HELPER));
    FlatHashtable.getOrCreate(table, "present", HELPER);
    assertNull(FlatHashtable.get(table, "still-missing", HELPER));
  }

  @Test
  void getOrCreate_returnsNullWhenTableIsFull() {
    // capacityFor(1) == 2 slots.
    Entry[] table = FlatHashtable.create(Entry.class, 1);
    assertTrue(FlatHashtable.getOrCreate(table, "k0", HELPER) != null);
    assertTrue(FlatHashtable.getOrCreate(table, "k1", HELPER) != null);
    // Both slots occupied by distinct keys -> a third distinct key finds no room.
    assertNull(FlatHashtable.getOrCreate(table, "k2", HELPER));
    // ...but an existing key still resolves even when full.
    assertSame(
        FlatHashtable.get(table, "k0", HELPER), FlatHashtable.getOrCreate(table, "k0", HELPER));
  }

  @Test
  void stringHelper_hashIsStableForEqualKeys() {
    assertEquals(HELPER.hash("route"), HELPER.hash(new String("route")));
  }
}
