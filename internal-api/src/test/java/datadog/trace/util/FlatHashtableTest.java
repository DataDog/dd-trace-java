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
   * Stateless concrete strategy over String keys, exposed as a canonical {@code INSTANCE} singleton
   * (private ctor) so the JIT can specialize each call site. {@code hashKey} defaults to {@code
   * key.hashCode()}; {@code hashOf} recomputes from the entry's key (this entry doesn't cache it),
   * consistently with that default.
   */
  static final class TestEntryStrategy extends FlatHashtable.EntryStrategy<TestEntry, String> {
    static final TestEntryStrategy INSTANCE = new TestEntryStrategy();

    private TestEntryStrategy() {}

    @Override
    public boolean matches(TestEntry entry, String key) {
      return key.equals(entry.key);
    }

    @Override
    public long hashOf(TestEntry entry) {
      return entry.key.hashCode(); // consistent with the default hashKey (key.hashCode())
    }
  }

  /** Non-capturing create strategy (a constructor method ref => singleton-cached, alloc-free). */
  private static final FlatHashtable.CreateStrategy<TestEntry, String> CREATE = TestEntry::new;

  /** All keys/entries hash to slot 0, so inserts chain by linear probing — exercises the probe. */
  static final class TestCollidingStrategy extends FlatHashtable.EntryStrategy<TestEntry, String> {
    static final TestCollidingStrategy INSTANCE = new TestCollidingStrategy();

    private TestCollidingStrategy() {}

    @Override
    public long hashKey(String key) {
      return 0;
    }

    @Override
    public boolean matches(TestEntry entry, String key) {
      return key.equals(entry.key);
    }

    @Override
    public long hashOf(TestEntry entry) {
      return 0;
    }
  }

  /**
   * Smallest hash {@code >= 1} that the table places on {@code slot} of a {@code mask + 1} table.
   */
  private static long hashLandingOn(int slot, int mask) {
    for (long h = 1; h < 1_000_000L; ++h) {
      if (FlatHashtable.home(h, mask) == slot) {
        return h;
      }
    }
    throw new AssertionError("no hash found landing on slot " + slot);
  }

  /**
   * All keys hash to the last slot of a 2-slot table (so probing wraps around to index 0). The
   * table owns the spread now, so we compute a hash that lands there rather than assuming {@code -1
   * & mask}.
   */
  static final class TestLastSlotStrategy extends FlatHashtable.EntryStrategy<TestEntry, String> {
    static final TestLastSlotStrategy INSTANCE = new TestLastSlotStrategy();
    private static final long LAST_SLOT_HASH = hashLandingOn(1, 1); // slot 1 of a 2-slot table

    private TestLastSlotStrategy() {}

    @Override
    public long hashKey(String key) {
      return LAST_SLOT_HASH;
    }

    @Override
    public boolean matches(TestEntry entry, String key) {
      return key.equals(entry.key);
    }

    @Override
    public long hashOf(TestEntry entry) {
      return LAST_SLOT_HASH;
    }
  }

  /**
   * Entry that caches its own hash (extends the Entry base) — for the entry-taking insert flavor.
   */
  static final class TestHashedEntry extends FlatHashtable.Entry {
    final String key;

    TestHashedEntry(String key) {
      super(key.hashCode()); // cache the default hashKey (key.hashCode())
      this.key = key;
    }
  }

  /** Strategy for {@link TestHashedEntry}: {@code hashOf} reads the cached hash. */
  static final class TestHashedStrategy
      extends FlatHashtable.EntryStrategy<TestHashedEntry, String> {
    static final TestHashedStrategy INSTANCE = new TestHashedStrategy();

    private TestHashedStrategy() {}

    @Override
    public boolean matches(TestHashedEntry entry, String key) {
      return key.equals(entry.key);
    }

    @Override
    public long hashOf(TestHashedEntry entry) {
      return entry.hash;
    }
  }

  /**
   * Case-insensitive strategy: {@code hashKey} sealed by the CI base, {@code matches} folds case.
   */
  static final class TestCaseInsensitiveStrategy
      extends FlatHashtable.CaseInsensitiveStringStrategy<TestEntry> {
    static final TestCaseInsensitiveStrategy INSTANCE = new TestCaseInsensitiveStrategy();

    private TestCaseInsensitiveStrategy() {}

    @Override
    public boolean matches(TestEntry entry, String key) {
      return key.equalsIgnoreCase(entry.key);
    }

    @Override
    public long hashOf(TestEntry entry) {
      return Strings.caseInsensitiveHashCode(entry.key);
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
    TestEntry first = FlatHashtable.getOrCreate(table, "a", TestEntryStrategy.INSTANCE, CREATE);
    assertEquals("a", first.key);
    // A second call must return the SAME instance, not mint a new one.
    assertSame(first, FlatHashtable.getOrCreate(table, "a", TestEntryStrategy.INSTANCE, CREATE));
    assertSame(first, FlatHashtable.get(table, "a", TestEntryStrategy.INSTANCE));
  }

  @Test
  void get_returnsNullForAbsentKey() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 8);
    assertNull(FlatHashtable.get(table, "missing", TestEntryStrategy.INSTANCE));
    FlatHashtable.getOrCreate(table, "present", TestEntryStrategy.INSTANCE, CREATE);
    assertNull(FlatHashtable.get(table, "still-missing", TestEntryStrategy.INSTANCE));
  }

  @Test
  void getOrCreate_returnsNullWhenTableIsFull() {
    // capacityFor(1) == 2 slots.
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 1);
    assertTrue(FlatHashtable.getOrCreate(table, "k0", TestEntryStrategy.INSTANCE, CREATE) != null);
    assertTrue(FlatHashtable.getOrCreate(table, "k1", TestEntryStrategy.INSTANCE, CREATE) != null);
    // Both slots occupied by distinct keys -> a third distinct key finds no room.
    assertNull(FlatHashtable.getOrCreate(table, "k2", TestEntryStrategy.INSTANCE, CREATE));
    // ...but an existing key still resolves even when full.
    assertSame(
        FlatHashtable.get(table, "k0", TestEntryStrategy.INSTANCE),
        FlatHashtable.getOrCreate(table, "k0", TestEntryStrategy.INSTANCE, CREATE));
  }

  @Test
  void hashKey_isStableForEqualKeys() {
    assertEquals(
        TestEntryStrategy.INSTANCE.hashKey("route"),
        TestEntryStrategy.INSTANCE.hashKey(new String("route")));
  }

  @Test
  void collision_probesPastOccupiedSlots_andResolvesEach() {
    // 8 slots; COLLIDING sends all to slot 0
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 4);
    TestEntry a = FlatHashtable.getOrCreate(table, "a", TestCollidingStrategy.INSTANCE, CREATE);
    // slot 0 taken -> 1
    TestEntry b = FlatHashtable.getOrCreate(table, "b", TestCollidingStrategy.INSTANCE, CREATE);
    // -> slot 2
    TestEntry c = FlatHashtable.getOrCreate(table, "c", TestCollidingStrategy.INSTANCE, CREATE);

    assertNotSame(a, b);
    assertNotSame(b, c);

    // each resolves via probe-past-occupied + match-after-probe
    assertSame(a, FlatHashtable.get(table, "a", TestCollidingStrategy.INSTANCE));
    assertSame(b, FlatHashtable.get(table, "b", TestCollidingStrategy.INSTANCE));
    assertSame(c, FlatHashtable.get(table, "c", TestCollidingStrategy.INSTANCE));

    // existing colliding key: found after probing, no new entry minted
    assertSame(b, FlatHashtable.getOrCreate(table, "b", TestCollidingStrategy.INSTANCE, CREATE));

    // absent key: probe past the 3 occupied slots, hit an empty slot -> null
    assertNull(FlatHashtable.get(table, "absent", TestCollidingStrategy.INSTANCE));
  }

  @Test
  void collision_probeWrapsAroundToFront() {
    // 2 slots (0,1), mask=1; LAST_SLOT starts at 1
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 1);
    // -> slot 1
    TestEntry k0 = FlatHashtable.getOrCreate(table, "k0", TestLastSlotStrategy.INSTANCE, CREATE);
    // taken -> wraps to 0
    TestEntry k1 = FlatHashtable.getOrCreate(table, "k1", TestLastSlotStrategy.INSTANCE, CREATE);

    assertNotSame(k0, k1);
    assertSame(k0, FlatHashtable.get(table, "k0", TestLastSlotStrategy.INSTANCE));
    // start slot 1 is occupied (no match) -> probe wraps to slot 0 -> match
    assertSame(k1, FlatHashtable.get(table, "k1", TestLastSlotStrategy.INSTANCE));
  }

  @Test
  void get_returnsNullWhenTableFullAndKeyAbsent() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 1); // 2 slots
    FlatHashtable.getOrCreate(table, "k0", TestCollidingStrategy.INSTANCE, CREATE);
    // fills slots 0 and 1
    FlatHashtable.getOrCreate(table, "k1", TestCollidingStrategy.INSTANCE, CREATE);

    // get() probes both occupied slots, wraps back to start -> null (get's full-wrap branch)
    assertNull(FlatHashtable.get(table, "absent", TestCollidingStrategy.INSTANCE));
  }

  @Test
  void insert_generalFlavor_placesViaHashOfAndResolves() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 8);
    TestEntry e = new TestEntry("a");
    // flavor 2: the home comes from TestEntryStrategy.INSTANCE.hashOf(e)
    assertTrue(FlatHashtable.insert(table, e, TestEntryStrategy.INSTANCE));
    assertSame(e, FlatHashtable.get(table, "a", TestEntryStrategy.INSTANCE));
  }

  @Test
  void insert_entryFlavor_placesViaCachedHashAndResolves() {
    TestHashedEntry[] table = FlatHashtable.create(TestHashedEntry.class, 8);
    TestHashedEntry e = new TestHashedEntry("a");
    // flavor 1: the home comes from the Entry's own cached hash, no strategy needed
    assertTrue(FlatHashtable.insert(table, e));
    assertSame(e, FlatHashtable.get(table, "a", TestHashedStrategy.INSTANCE));
  }

  @Test
  void insert_returnsFalseWhenFull() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 1); // 2 slots
    assertTrue(FlatHashtable.insert(table, new TestEntry("k0"), TestEntryStrategy.INSTANCE));
    assertTrue(FlatHashtable.insert(table, new TestEntry("k1"), TestEntryStrategy.INSTANCE));
    // no room
    assertFalse(FlatHashtable.insert(table, new TestEntry("k2"), TestEntryStrategy.INSTANCE));
  }

  @Test
  void forEach_visitsEveryEntry() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 8);
    FlatHashtable.getOrCreate(table, "a", TestEntryStrategy.INSTANCE, CREATE);
    FlatHashtable.getOrCreate(table, "b", TestEntryStrategy.INSTANCE, CREATE);
    FlatHashtable.getOrCreate(table, "c", TestEntryStrategy.INSTANCE, CREATE);

    Set<String> seen = new HashSet<>();
    FlatHashtable.forEach(table, e -> seen.add(e.key));
    assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")), seen);
  }

  @Test
  void forEach_contextVariant_passesContextWithoutCapture() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 8);
    FlatHashtable.getOrCreate(table, "a", TestEntryStrategy.INSTANCE, CREATE);
    FlatHashtable.getOrCreate(table, "b", TestEntryStrategy.INSTANCE, CREATE);

    Set<String> seen = new HashSet<>();
    FlatHashtable.forEach(table, seen, (ctx, e) -> ctx.add(e.key));
    assertEquals(new HashSet<>(Arrays.asList("a", "b")), seen);
  }

  @Test
  void iterator_yieldsEveryEntrySharingTheHash() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 4); // COLLIDING sends all to slot 0
    TestEntry a = FlatHashtable.getOrCreate(table, "a", TestCollidingStrategy.INSTANCE, CREATE);
    TestEntry b = FlatHashtable.getOrCreate(table, "b", TestCollidingStrategy.INSTANCE, CREATE);
    TestEntry c = FlatHashtable.getOrCreate(table, "c", TestCollidingStrategy.INSTANCE, CREATE);

    Set<TestEntry> seen = new HashSet<>();
    Iterator<TestEntry> it = FlatHashtable.iterator(table, 0, TestCollidingStrategy.INSTANCE);
    while (it.hasNext()) {
      seen.add(it.next());
    }
    assertEquals(new HashSet<>(Arrays.asList(a, b, c)), seen);
  }

  @Test
  void iterator_filtersOutEntriesWithADifferentHash() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 4); // entries at slot 0, hashOf == 0
    FlatHashtable.getOrCreate(table, "a", TestCollidingStrategy.INSTANCE, CREATE);
    FlatHashtable.getOrCreate(table, "b", TestCollidingStrategy.INSTANCE, CREATE);

    // a hash that shares the entries' home slot (0) but that no stored entry has as its hashOf
    long sameHomeOtherHash = hashLandingOn(0, table.length - 1);
    Iterator<TestEntry> it =
        FlatHashtable.iterator(table, sameHomeOtherHash, TestCollidingStrategy.INSTANCE);
    assertFalse(it.hasNext());
  }

  @Test
  void iterator_emptyRunHasNoNext() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 4);
    Iterator<TestEntry> it = FlatHashtable.iterator(table, 0, TestCollidingStrategy.INSTANCE);
    assertFalse(it.hasNext());
    assertThrows(NoSuchElementException.class, it::next);
  }

  @Test
  void capacityFor_honorsLoadFactor() {
    // default 0.5 -> >= 2x; LOW 0.25 -> >= 4x.
    assertEquals(8, FlatHashtable.capacityFor(4, FlatHashtable.DEFAULT_LOAD_FACTOR));
    assertEquals(16, FlatHashtable.capacityFor(4, FlatHashtable.LOW_LOAD_FACTOR));
    // capacityFor(cardinalityLimit) delegates to the default.
    assertEquals(FlatHashtable.capacityFor(6), FlatHashtable.capacityFor(6, 0.5f));
  }

  @Test
  void capacityFor_rejectsLoadFactorOutOfRange() {
    assertThrows(IllegalArgumentException.class, () -> FlatHashtable.capacityFor(4, 0f));
    assertThrows(IllegalArgumentException.class, () -> FlatHashtable.capacityFor(4, 1f));
    assertThrows(IllegalArgumentException.class, () -> FlatHashtable.capacityFor(4, -0.1f));
  }

  @Test
  void create_honorsLoadFactor() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 4, FlatHashtable.LOW_LOAD_FACTOR);
    assertEquals(16, table.length);
  }

  @Test
  void resize_generalFlavor_growsAndKeepsEveryEntryFindable() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 1); // 2 slots
    FlatHashtable.insert(table, new TestEntry("a"), TestEntryStrategy.INSTANCE);
    FlatHashtable.insert(table, new TestEntry("b"), TestEntryStrategy.INSTANCE);

    TestEntry[] grown = FlatHashtable.resize(table, TestEntryStrategy.INSTANCE);
    assertEquals(4, grown.length);
    assertNotSame(table, grown);
    assertEquals("a", FlatHashtable.get(grown, "a", TestEntryStrategy.INSTANCE).key);
    assertEquals("b", FlatHashtable.get(grown, "b", TestEntryStrategy.INSTANCE).key);
  }

  @Test
  void resize_entryFlavor_growsAndKeepsEveryEntryFindable() {
    TestHashedEntry[] table = FlatHashtable.create(TestHashedEntry.class, 1); // 2 slots
    FlatHashtable.insert(table, new TestHashedEntry("a"));
    FlatHashtable.insert(table, new TestHashedEntry("b"));

    TestHashedEntry[] grown = FlatHashtable.resize(table);
    assertEquals(4, grown.length);
    assertEquals("a", FlatHashtable.get(grown, "a", TestHashedStrategy.INSTANCE).key);
    assertEquals("b", FlatHashtable.get(grown, "b", TestHashedStrategy.INSTANCE).key);
  }

  @Test
  void resizingInsert_generalFlavor_growsPastCapacityAndReturnsTheTable() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 1); // 2 slots
    for (int i = 0; i < 10; ++i) {
      table =
          FlatHashtable.resizingInsert(table, new TestEntry("k" + i), TestEntryStrategy.INSTANCE);
    }
    assertTrue(table.length >= 16); // grew from 2 to hold 10 at load factor <= 0.5
    for (int i = 0; i < 10; ++i) {
      assertEquals("k" + i, FlatHashtable.get(table, "k" + i, TestEntryStrategy.INSTANCE).key);
    }
  }

  @Test
  void resizingInsert_entryFlavor_growsPastCapacityAndReturnsTheTable() {
    TestHashedEntry[] table = FlatHashtable.create(TestHashedEntry.class, 1); // 2 slots
    for (int i = 0; i < 10; ++i) {
      table = FlatHashtable.resizingInsert(table, new TestHashedEntry("k" + i));
    }
    assertTrue(table.length >= 16);
    for (int i = 0; i < 10; ++i) {
      assertEquals("k" + i, FlatHashtable.get(table, "k" + i, TestHashedStrategy.INSTANCE).key);
    }
  }

  /** Entry with an explicitly-set cached hash — lets a test force a shared-hash bucket. */
  static final class TestHashEntry extends FlatHashtable.Entry {
    final String key;

    TestHashEntry(String key, long hash) {
      super(hash);
      this.key = key;
    }
  }

  @Test
  void entryIterator_yieldsEveryEntrySharingTheHash() {
    TestHashEntry[] table = FlatHashtable.create(TestHashEntry.class, 4);
    TestHashEntry a = new TestHashEntry("a", 0);
    TestHashEntry b = new TestHashEntry("b", 0);
    TestHashEntry c = new TestHashEntry("c", 0);
    FlatHashtable.insert(table, a);
    FlatHashtable.insert(table, b);
    FlatHashtable.insert(table, c);

    Set<TestHashEntry> seen = new HashSet<>();
    // 2-arg Entry overload -> the specialized iterator (constant strategy, inlined hashOf).
    Iterator<TestHashEntry> it = FlatHashtable.iterator(table, 0);
    while (it.hasNext()) {
      seen.add(it.next());
    }
    assertEquals(new HashSet<>(Arrays.asList(a, b, c)), seen);
  }

  @Test
  void entryIterator_filtersOutEntriesWithADifferentHash() {
    TestHashEntry[] table = FlatHashtable.create(TestHashEntry.class, 4); // 8 slots, mask 7
    FlatHashtable.insert(table, new TestHashEntry("a", 0));
    FlatHashtable.insert(table, new TestHashEntry("b", 0));
    // shares the home slot (0) but a different hash -> must be filtered out
    long otherHash = hashLandingOn(0, table.length - 1);
    FlatHashtable.insert(table, new TestHashEntry("c", otherHash));

    int yielded = 0;
    Iterator<TestHashEntry> it = FlatHashtable.iterator(table, 0);
    while (it.hasNext()) {
      assertEquals(0, it.next().hash);
      yielded++;
    }
    assertEquals(2, yielded);
  }

  @Test
  void entryIterator_emptyRunHasNoNext() {
    TestHashEntry[] table = FlatHashtable.create(TestHashEntry.class, 4);
    Iterator<TestHashEntry> it = FlatHashtable.iterator(table, 0);
    assertFalse(it.hasNext());
    assertThrows(NoSuchElementException.class, it::next);
  }

  @Test
  void caseInsensitiveStrategy_matchesRegardlessOfCase() {
    TestEntry[] table = FlatHashtable.create(TestEntry.class, 4);
    TestEntry stored =
        FlatHashtable.getOrCreate(
            table, "Content-Type", TestCaseInsensitiveStrategy.INSTANCE, CREATE);

    // Look-ups in any case resolve to the same stored entry, allocation-free.
    assertSame(
        stored, FlatHashtable.get(table, "content-type", TestCaseInsensitiveStrategy.INSTANCE));
    assertSame(
        stored, FlatHashtable.get(table, "CONTENT-TYPE", TestCaseInsensitiveStrategy.INSTANCE));
    assertSame(
        stored, FlatHashtable.get(table, "cOnTeNt-TyPe", TestCaseInsensitiveStrategy.INSTANCE));
    // getOrCreate with a differently-cased key does not mint a second entry.
    assertSame(
        stored,
        FlatHashtable.getOrCreate(
            table, "CONTENT-TYPE", TestCaseInsensitiveStrategy.INSTANCE, CREATE));
    assertNull(FlatHashtable.get(table, "content-length", TestCaseInsensitiveStrategy.INSTANCE));
  }
}
