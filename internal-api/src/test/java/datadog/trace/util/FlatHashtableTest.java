package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FlatHashtableTest {

  /** Self-contained entry: carries its own key (the identity FlatHashtable relies on). */
  static final class TestEntry {
    final String key;

    TestEntry(String key) {
      this.key = key;
    }
  }

  /**
   * Stateless concrete key strategy over String keys, exposed as a canonical {@code INSTANCE}
   * singleton (private ctor) so the JIT can specialize each call site. {@code hash} is sealed by
   * {@link FlatHashtable.StringKeyStrategy}; {@code hashOf} recomputes from the entry's key (this
   * entry doesn't cache its hash).
   */
  static final class TestEntryKeyStrategy extends FlatHashtable.StringKeyStrategy<TestEntry> {
    static final TestEntryKeyStrategy INSTANCE = new TestEntryKeyStrategy();

    private TestEntryKeyStrategy() {}

    @Override
    public boolean matches(String key, TestEntry entry) {
      return key.equals(entry.key);
    }

    @Override
    public long hashOf(TestEntry entry) {
      return hash(entry.key);
    }
  }

  /** Non-capturing create strategy (a constructor method ref => singleton-cached, alloc-free). */
  private static final FlatHashtable.CreateStrategy<String, TestEntry> CREATE = TestEntry::new;

  /** All keys hash to slot 0, so inserts chain by linear probing — exercises the probe path. */
  static final class TestCollidingKeyStrategy extends FlatHashtable.KeyStrategy<String, TestEntry> {
    static final TestCollidingKeyStrategy INSTANCE = new TestCollidingKeyStrategy();

    private TestCollidingKeyStrategy() {}

    @Override
    public long hash(String key) {
      return 0;
    }

    @Override
    public boolean matches(String key, TestEntry entry) {
      return key.equals(entry.key);
    }

    @Override
    public long hashOf(TestEntry entry) {
      return hash(entry.key);
    }
  }

  /** All keys hash to the last slot ({@code -1 & mask}), so probing wraps around to index 0. */
  static final class TestLastSlotKeyStrategy extends FlatHashtable.KeyStrategy<String, TestEntry> {
    static final TestLastSlotKeyStrategy INSTANCE = new TestLastSlotKeyStrategy();

    private TestLastSlotKeyStrategy() {}

    @Override
    public long hash(String key) {
      return -1;
    }

    @Override
    public boolean matches(String key, TestEntry entry) {
      return key.equals(entry.key);
    }

    @Override
    public long hashOf(TestEntry entry) {
      return hash(entry.key);
    }
  }

  /**
   * Entry that caches its own hash (extends the Entry base) — for the entry-taking insert flavor.
   */
  static final class TestHashedEntry extends FlatHashtable.Entry {
    final String key;

    TestHashedEntry(String key) {
      // cache the same hash TestEntryKeyStrategy uses for lookups
      super(TestEntryKeyStrategy.INSTANCE.hash(key));
      this.key = key;
    }
  }

  /** Key strategy for {@link TestHashedEntry}: {@code hashOf} is sealed to the cached hash. */
  static final class TestHashedKeyStrategy
      extends FlatHashtable.EntryKeyStrategy<String, TestHashedEntry> {
    static final TestHashedKeyStrategy INSTANCE = new TestHashedKeyStrategy();

    private TestHashedKeyStrategy() {}

    @Override
    public long hash(String key) {
      return TestEntryKeyStrategy.INSTANCE.hash(key);
    }

    @Override
    public boolean matches(String key, TestHashedEntry entry) {
      return key.equals(entry.key);
    }
  }

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
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 4);
    assertEquals(8, table.length);
    assertEquals(TestEntry.class, table.getClass().getComponentType());
  }

  @Test
  void getOrCreate_insertsOnceAndReturnsTheExistingEntry() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 8);
    TestEntry first = FlatHashtable.getOrCreate(table, "a", TestEntryKeyStrategy.INSTANCE, CREATE);
    assertEquals("a", first.key);
    // A second call must return the SAME instance, not mint a new one.
    assertSame(first, FlatHashtable.getOrCreate(table, "a", TestEntryKeyStrategy.INSTANCE, CREATE));
    assertSame(first, FlatHashtable.get(table, "a", TestEntryKeyStrategy.INSTANCE));
  }

  @Test
  void get_returnsNullForAbsentKey() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 8);
    assertNull(FlatHashtable.get(table, "missing", TestEntryKeyStrategy.INSTANCE));
    FlatHashtable.getOrCreate(table, "present", TestEntryKeyStrategy.INSTANCE, CREATE);
    assertNull(FlatHashtable.get(table, "still-missing", TestEntryKeyStrategy.INSTANCE));
  }

  @Test
  void getOrCreate_returnsNullWhenTableIsFull() {
    // capacityFor(1) == 2 slots.
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 1);
    assertTrue(
        FlatHashtable.getOrCreate(table, "k0", TestEntryKeyStrategy.INSTANCE, CREATE) != null);
    assertTrue(
        FlatHashtable.getOrCreate(table, "k1", TestEntryKeyStrategy.INSTANCE, CREATE) != null);
    // Both slots occupied by distinct keys -> a third distinct key finds no room.
    assertNull(FlatHashtable.getOrCreate(table, "k2", TestEntryKeyStrategy.INSTANCE, CREATE));
    // ...but an existing key still resolves even when full.
    assertSame(
        FlatHashtable.get(table, "k0", TestEntryKeyStrategy.INSTANCE),
        FlatHashtable.getOrCreate(table, "k0", TestEntryKeyStrategy.INSTANCE, CREATE));
  }

  @Test
  void stringKeyStrategy_hashIsStableForEqualKeys() {
    assertEquals(
        TestEntryKeyStrategy.INSTANCE.hash("route"),
        TestEntryKeyStrategy.INSTANCE.hash(new String("route")));
  }

  @Test
  void collision_probesPastOccupiedSlots_andResolvesEach() {
    // 8 slots; COLLIDING sends all to slot 0
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 4);
    TestEntry a = FlatHashtable.getOrCreate(table, "a", TestCollidingKeyStrategy.INSTANCE, CREATE);
    // slot 0 taken -> 1
    TestEntry b = FlatHashtable.getOrCreate(table, "b", TestCollidingKeyStrategy.INSTANCE, CREATE);
    // -> slot 2
    TestEntry c = FlatHashtable.getOrCreate(table, "c", TestCollidingKeyStrategy.INSTANCE, CREATE);

    assertNotSame(a, b);
    assertNotSame(b, c);

    // each resolves via probe-past-occupied + match-after-probe
    assertSame(a, FlatHashtable.get(table, "a", TestCollidingKeyStrategy.INSTANCE));
    assertSame(b, FlatHashtable.get(table, "b", TestCollidingKeyStrategy.INSTANCE));
    assertSame(c, FlatHashtable.get(table, "c", TestCollidingKeyStrategy.INSTANCE));

    // existing colliding key: found after probing, no new entry minted
    assertSame(b, FlatHashtable.getOrCreate(table, "b", TestCollidingKeyStrategy.INSTANCE, CREATE));

    // absent key: probe past the 3 occupied slots, hit an empty slot -> null
    assertNull(FlatHashtable.get(table, "absent", TestCollidingKeyStrategy.INSTANCE));
  }

  @Test
  void collision_probeWrapsAroundToFront() {
    // 2 slots (0,1), mask=1; LAST_SLOT starts at 1
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 1);
    // -> slot 1
    TestEntry k0 = FlatHashtable.getOrCreate(table, "k0", TestLastSlotKeyStrategy.INSTANCE, CREATE);
    // taken -> wraps to 0
    TestEntry k1 = FlatHashtable.getOrCreate(table, "k1", TestLastSlotKeyStrategy.INSTANCE, CREATE);

    assertNotSame(k0, k1);
    assertSame(k0, FlatHashtable.get(table, "k0", TestLastSlotKeyStrategy.INSTANCE));
    // start slot 1 is occupied (no match) -> probe wraps to slot 0 -> match
    assertSame(k1, FlatHashtable.get(table, "k1", TestLastSlotKeyStrategy.INSTANCE));
  }

  @Test
  void get_returnsNullWhenTableFullAndKeyAbsent() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 1); // 2 slots
    FlatHashtable.getOrCreate(table, "k0", TestCollidingKeyStrategy.INSTANCE, CREATE);
    // fills slots 0 and 1
    FlatHashtable.getOrCreate(table, "k1", TestCollidingKeyStrategy.INSTANCE, CREATE);

    // get() probes both occupied slots, wraps back to start -> null (get's full-wrap branch)
    assertNull(FlatHashtable.get(table, "absent", TestCollidingKeyStrategy.INSTANCE));
  }

  @Test
  void insert_generalFlavor_placesViaHashOfAndResolves() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 8);
    TestEntry e = new TestEntry("a");
    // flavor 2: the home comes from TestEntryKeyStrategy.INSTANCE.hashOf(e)
    assertTrue(FlatHashtable.insert(table, e, TestEntryKeyStrategy.INSTANCE));
    assertSame(e, FlatHashtable.get(table, "a", TestEntryKeyStrategy.INSTANCE));
  }

  @Test
  void insert_entryFlavor_placesViaCachedHashAndResolves() {
    TestHashedEntry[] table = FlatHashtable.create(TestHashedEntry.class, 8);
    TestHashedEntry e = new TestHashedEntry("a");
    // flavor 1: the home comes from the Entry's own cached hash, no strategy needed
    assertTrue(FlatHashtable.insert(table, e));
    assertSame(e, FlatHashtable.get(table, "a", TestHashedKeyStrategy.INSTANCE));
  }

  @Test
  void insert_returnsFalseWhenFull() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 1); // 2 slots
    assertTrue(FlatHashtable.insert(table, new TestEntry("k0"), TestEntryKeyStrategy.INSTANCE));
    assertTrue(FlatHashtable.insert(table, new TestEntry("k1"), TestEntryKeyStrategy.INSTANCE));
    // no room
    assertFalse(FlatHashtable.insert(table, new TestEntry("k2"), TestEntryKeyStrategy.INSTANCE));
  }

  @Test
  void forEach_visitsEveryEntry() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 8);
    FlatHashtable.getOrCreate(table, "a", TestEntryKeyStrategy.INSTANCE, CREATE);
    FlatHashtable.getOrCreate(table, "b", TestEntryKeyStrategy.INSTANCE, CREATE);
    FlatHashtable.getOrCreate(table, "c", TestEntryKeyStrategy.INSTANCE, CREATE);

    Set<String> seen = new HashSet<>();
    FlatHashtable.forEach(table, e -> seen.add(e.key));
    assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")), seen);
  }

  @Test
  void forEach_contextVariant_passesContextWithoutCapture() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 8);
    FlatHashtable.getOrCreate(table, "a", TestEntryKeyStrategy.INSTANCE, CREATE);
    FlatHashtable.getOrCreate(table, "b", TestEntryKeyStrategy.INSTANCE, CREATE);

    Set<String> seen = new HashSet<>();
    FlatHashtable.forEach(table, seen, (ctx, e) -> ctx.add(e.key));
    assertEquals(new HashSet<>(Arrays.asList("a", "b")), seen);
  }

  @Test
  void iterator_yieldsEveryEntrySharingTheHash() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 4); // COLLIDING sends all to slot 0
    TestEntry a = FlatHashtable.getOrCreate(table, "a", TestCollidingKeyStrategy.INSTANCE, CREATE);
    TestEntry b = FlatHashtable.getOrCreate(table, "b", TestCollidingKeyStrategy.INSTANCE, CREATE);
    TestEntry c = FlatHashtable.getOrCreate(table, "c", TestCollidingKeyStrategy.INSTANCE, CREATE);

    Set<TestEntry> seen = new HashSet<>();
    Iterator<TestEntry> it = FlatHashtable.iterator(table, 0, TestCollidingKeyStrategy.INSTANCE);
    while (it.hasNext()) {
      seen.add(it.next());
    }
    assertEquals(new HashSet<>(Arrays.asList(a, b, c)), seen);
  }

  @Test
  void iterator_filtersOutEntriesWithADifferentHash() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 4); // entries at slot 0, hashOf == 0
    FlatHashtable.getOrCreate(table, "a", TestCollidingKeyStrategy.INSTANCE, CREATE);
    FlatHashtable.getOrCreate(table, "b", TestCollidingKeyStrategy.INSTANCE, CREATE);

    // hash 8 shares the home slot (8 & 7 == 0) but no stored entry has hashOf == 8
    Iterator<TestEntry> it = FlatHashtable.iterator(table, 8, TestCollidingKeyStrategy.INSTANCE);
    assertFalse(it.hasNext());
  }

  @Test
  void iterator_emptyRunHasNoNext() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 4);
    Iterator<TestEntry> it = FlatHashtable.iterator(table, 0, TestCollidingKeyStrategy.INSTANCE);
    assertFalse(it.hasNext());
    assertThrows(NoSuchElementException.class, it::next);
  }
}
