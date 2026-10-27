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

import java.util.HashMap;
import java.util.Map;
import java.util.function.ObjLongConsumer;
import org.junit.jupiter.api.Test;

class HashtableD1Test {

  @Test
  void emptyTableLookupReturnsNull() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    assertNull(table.get("missing"));
    assertEquals(0, table.size());
  }

  @Test
  void insertedEntryIsRetrievable() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    StringIntEntry e = new StringIntEntry("foo", 1);
    table.insert(e);
    assertEquals(1, table.size());
    assertSame(e, table.get("foo"));
  }

  @Test
  void keyExposesTheConstructionKey() {
    StringIntEntry e = new StringIntEntry("foo", 1);
    assertEquals("foo", e.key());
  }

  @Test
  void multipleInsertsRetrievableSeparately() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 16);
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
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    table.insert(new StringIntEntry("counter", 0));
    for (int i = 0; i < 10; i++) {
      StringIntEntry e = table.get("counter");
      e.value++;
    }
    assertEquals(10, table.get("counter").value);
  }

  @Test
  void removeUnlinksEntryAndDecrementsSize() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
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
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    table.insert(new StringIntEntry("a", 1));
    assertNull(table.remove("nope"));
    assertEquals(1, table.size());
  }

  @Test
  void removeIfUnlinksMatchingEntriesAndDecrementsSize() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    table.insert(new StringIntEntry("a", 1));
    table.insert(new StringIntEntry("b", 2));
    table.insert(new StringIntEntry("c", 3));

    assertTrue(table.removeIf(e -> e.value % 2 == 1));

    assertEquals(1, table.size());
    assertNull(table.get("a"));
    assertNotNull(table.get("b"));
    assertNull(table.get("c"));
  }

  @Test
  void removeIfReturnsFalseAndLeavesTableUntouchedWhenNothingMatches() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    table.insert(new StringIntEntry("a", 1));

    assertFalse(table.removeIf(e -> false));

    assertEquals(1, table.size());
    assertNotNull(table.get("a"));
  }

  @Test
  void tryGetOrCreateOrEvictInsertsWithoutEvictingWhenUnderCapacity() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);

    Maybe<StringIntEntry> created =
        table.tryGetOrCreateOrEvict("a", k -> new StringIntEntry(k, 1), e -> true);

    assertTrue(created.isPresent());
    assertEquals(1, table.size());
    assertSame(created.getOrNull(), table.get("a"));
  }

  @Test
  void tryGetOrCreateOrEvictReturnsExistingEntryOnHitWithoutEvicting() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 1);
    StringIntEntry a = table.tryGetOrCreateOrNull("a", k -> new StringIntEntry(k, 1));

    Maybe<StringIntEntry> got =
        table.tryGetOrCreateOrEvict(
            "a",
            k -> {
              throw new AssertionError("creator must not run on a hit");
            },
            e -> {
              throw new AssertionError("evictable must not run on a hit");
            });

    assertSame(a, got.getOrNull());
    assertEquals(1, table.size());
  }

  @Test
  void tryGetOrCreateOrEvictEvictsWhenFullAndInsertsNewEntry() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 1);
    table.tryGetOrCreateOrNull("old", k -> new StringIntEntry(k, 1));
    assertTrue(table.isFull());

    Maybe<StringIntEntry> created =
        table.tryGetOrCreateOrEvict("new", k -> new StringIntEntry(k, 2), e -> true);

    assertTrue(created.isPresent());
    assertEquals(1, table.size());
    assertNull(table.get("old"));
    assertSame(created.getOrNull(), table.get("new"));
  }

  @Test
  void tryGetOrCreateOrEvictOrNullRefusesWhenFullAndNothingEvictable() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 1);
    table.tryGetOrCreateOrNull("old", k -> new StringIntEntry(k, 1));

    StringIntEntry result =
        table.tryGetOrCreateOrEvictOrNull("new", k -> new StringIntEntry(k, 2), e -> false);

    assertNull(result);
    assertEquals(1, table.size());
    assertNotNull(table.get("old"));
    assertNull(table.get("new"));
  }

  @Test
  void tryGetOrCreateOrEvictOrNullEvictionRunsBeforeThrowingCreator() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 1);
    table.tryGetOrCreateOrNull("old", k -> new StringIntEntry(k, 1));

    assertThrows(
        RuntimeException.class,
        () ->
            table.tryGetOrCreateOrEvictOrNull(
                "new",
                k -> {
                  throw new RuntimeException("boom");
                },
                e -> true));

    // Eviction already happened before the creator threw: the table is left one entry smaller,
    // not corrupted or double-booked.
    assertEquals(0, table.size());
    assertNull(table.get("old"));
    assertNull(table.get("new"));
  }

  @Test
  void tryInsertOrReplaceInsertsThenReplacesWithoutGrowing() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    StringIntEntry first = new StringIntEntry("k", 1);
    assertTrue(table.tryInsertOrReplace(first), "fresh insert accepted");
    assertEquals(1, table.size());

    StringIntEntry second = new StringIntEntry("k", 2);
    assertTrue(table.tryInsertOrReplace(second), "replace accepted");
    assertEquals(1, table.size(), "replacing an existing key does not grow the table");
    assertSame(second, table.get("k"), "new entry visible after replace");
  }

  @Test
  void clearEmptiesTheTable() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
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
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
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
  void forEachWithContextPassesContextToConsumer() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    table.insert(new StringIntEntry("a", 10));
    table.insert(new StringIntEntry("b", 20));
    table.insert(new StringIntEntry("c", 30));
    Map<String, Integer> seen = new HashMap<>();
    table.forEach(seen, (ctx, e) -> ctx.put(e.key, e.value));
    assertEquals(3, seen.size());
    assertEquals(10, seen.get("a"));
    assertEquals(20, seen.get("b"));
    assertEquals(30, seen.get("c"));
  }

  @Test
  void forEachWithContextOnEmptyTableDoesNothing() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    Map<String, Integer> seen = new HashMap<>();
    table.forEach(seen, (ctx, e) -> ctx.put(e.key, e.value));
    assertEquals(0, seen.size());
  }

  @Test
  void nullKeyIsPermittedAndDistinctFromAbsent() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
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
    Hashtable.D1<CollidingKey, CollidingKeyEntry> table =
        Hashtable.D1.createBounded(CollidingKeyEntry.class, 4);
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
    Hashtable.D1<CollidingKey, CollidingKeyEntry> table =
        Hashtable.D1.createBounded(CollidingKeyEntry.class, 4);
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

  @Test
  void getOrCreateOnMissBuildsEntryViaCreator() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    int[] createCount = {0};
    StringIntEntry created =
        table.tryGetOrCreateOrNull(
            "foo",
            k -> {
              createCount[0]++;
              return new StringIntEntry(k, 42);
            });
    assertNotNull(created);
    assertEquals("foo", created.key);
    assertEquals(42, created.value);
    assertEquals(1, table.size());
    assertEquals(1, createCount[0]);
    assertSame(created, table.get("foo"));
  }

  @Test
  void getOrCreateOnHitSkipsCreator() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    StringIntEntry seeded = new StringIntEntry("foo", 1);
    table.insert(seeded);
    int[] createCount = {0};
    StringIntEntry got =
        table.tryGetOrCreateOrNull(
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
  void getOrCreateNullKeyIsPermitted() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    StringIntEntry created = table.tryGetOrCreateOrNull(null, k -> new StringIntEntry(k, 7));
    assertNotNull(created);
    assertNull(created.key);
    assertEquals(7, created.value);
    assertSame(created, table.tryGetOrCreateOrNull(null, k -> new StringIntEntry(k, 999)));
    assertEquals(1, table.size());
  }

  @Test
  void getOrCreateAsMaybeOnMissBuildsEntryViaCreator() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    Maybe<StringIntEntry> maybe = table.tryGetOrCreate("foo", k -> new StringIntEntry(k, 42));
    assertTrue(maybe.isPresent());
    assertEquals(42, maybe.getOrNull().value);
    assertSame(table.get("foo"), maybe.getOrNull());
  }

  @Test
  void getOrCreateAsMaybeReturnsAbsentOnceAtCapacityButStillReturnsHits() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 2);
    table.insert(new StringIntEntry("a", 1));
    table.insert(new StringIntEntry("b", 2));

    assertFalse(table.tryGetOrCreate("c", k -> new StringIntEntry(k, 3)).isPresent());
    assertEquals(2, table.size());

    Maybe<StringIntEntry> hit = table.tryGetOrCreate("a", k -> new StringIntEntry(k, 999));
    assertEquals(1, hit.getOrNull().value, "existing entry is still returned even at capacity");
  }

  @Test
  void getOrCreateAsMaybeUpdateAppliesOnlyWhenPresent() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 1);
    table.insert(new StringIntEntry("a", 1));

    ObjLongConsumer<StringIntEntry> add = (e, n) -> e.value += n;
    table.tryGetOrCreate("a", k -> new StringIntEntry(k, 0)).update(5L, add);
    assertEquals(6, table.get("a").value);

    table.tryGetOrCreate("b", k -> new StringIntEntry(k, 0)).update(5L, add);
    assertNull(table.get("b"), "refused create at capacity leaves nothing to update");
  }

  @Test
  void insertReturnsFalseOnceAtCapacity() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 2);
    assertTrue(table.insert(new StringIntEntry("a", 1)));
    assertTrue(table.insert(new StringIntEntry("b", 2)));
    assertFalse(table.insert(new StringIntEntry("c", 3)));
    assertEquals(2, table.size());
    assertNull(table.get("c"));
  }

  @Test
  void getOrCreateReturnsNullOnceAtCapacityButStillReturnsHits() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 2);
    table.insert(new StringIntEntry("a", 1));
    table.insert(new StringIntEntry("b", 2));

    assertNull(table.tryGetOrCreateOrNull("c", k -> new StringIntEntry(k, 3)));
    assertEquals(2, table.size());

    StringIntEntry hit = table.tryGetOrCreateOrNull("a", k -> new StringIntEntry(k, 999));
    assertEquals(1, hit.value, "existing entry is still returned even at capacity");
  }

  @Test
  void tryInsertOrReplaceStillReplacesAtCapacityButRefusesFreshInsert() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 2);
    table.insert(new StringIntEntry("a", 1));
    table.insert(new StringIntEntry("b", 2));

    StringIntEntry replacement = new StringIntEntry("a", 99);
    assertTrue(
        table.tryInsertOrReplace(replacement), "replacing an existing key succeeds when full");
    assertSame(replacement, table.get("a"));
    assertEquals(2, table.size());

    assertFalse(
        table.tryInsertOrReplace(new StringIntEntry("c", 3)),
        "a fresh insert is refused, not thrown");
    assertEquals(2, table.size());
    assertNull(table.get("c"));
  }

  @Test
  void isFullReflectsCapacity() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 2);
    assertFalse(table.isFull());
    table.insert(new StringIntEntry("a", 1));
    assertFalse(table.isFull());
    table.insert(new StringIntEntry("b", 2));
    assertTrue(table.isFull());
    table.remove("a");
    assertFalse(table.isFull());
  }

  @Test
  void drainVisitsEveryEntryThenEmptiesTable() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    table.insert(new StringIntEntry("a", 1));
    table.insert(new StringIntEntry("b", 2));
    Map<String, Integer> drained = new HashMap<>();

    table.drain(e -> drained.put(e.key, e.value));

    assertEquals(2, drained.size());
    assertEquals(1, drained.get("a"));
    assertEquals(2, drained.get("b"));
    assertEquals(0, table.size());
    assertNull(table.get("a"));
    assertNull(table.get("b"));

    // Table is reusable after drain.
    table.insert(new StringIntEntry("c", 3));
    assertEquals(1, table.size());
    assertEquals(3, table.get("c").value);
  }

  @Test
  void drainWithContextPassesContextToSink() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    table.insert(new StringIntEntry("a", 1));
    table.insert(new StringIntEntry("b", 2));
    Map<String, Integer> drained = new HashMap<>();

    table.drain(drained, (ctx, e) -> ctx.put(e.key, e.value));

    assertEquals(2, drained.size());
    assertEquals(0, table.size());
  }

  @Test
  void drainOnEmptyTableDoesNothing() {
    Hashtable.D1<String, StringIntEntry> table =
        Hashtable.D1.createBounded(StringIntEntry.class, 8);
    Map<String, Integer> drained = new HashMap<>();
    table.drain(e -> drained.put(e.key, e.value));
    assertEquals(0, drained.size());
    assertEquals(0, table.size());
  }
}
