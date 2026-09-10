package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HashtableD2Test {

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

  @Test
  void forEachWithContextPassesContextToConsumer() {
    Hashtable.D2<String, Integer, PairEntry> table = new Hashtable.D2<>(8);
    table.insert(new PairEntry("a", 1, 100));
    table.insert(new PairEntry("b", 2, 200));
    Set<String> seen = new HashSet<>();
    table.forEach(seen, (ctx, e) -> ctx.add(e.key1 + ":" + e.key2));
    assertEquals(2, seen.size());
    assertTrue(seen.contains("a:1"));
    assertTrue(seen.contains("b:2"));
  }

  @Test
  void getOrCreateOnMissBuildsEntryViaCreator() {
    Hashtable.D2<String, Integer, PairEntry> table = new Hashtable.D2<>(8);
    int[] createCount = {0};
    PairEntry created =
        table.getOrCreate(
            "a",
            1,
            (k1, k2) -> {
              createCount[0]++;
              return new PairEntry(k1, k2, 100);
            });
    assertNotNull(created);
    assertEquals("a", created.key1);
    assertEquals(Integer.valueOf(1), created.key2);
    assertEquals(100, created.value);
    assertEquals(1, table.size());
    assertEquals(1, createCount[0]);
    assertSame(created, table.get("a", 1));
  }

  @Test
  void getOrCreateOnHitSkipsCreator() {
    Hashtable.D2<String, Integer, PairEntry> table = new Hashtable.D2<>(8);
    PairEntry seeded = new PairEntry("a", 1, 100);
    table.insert(seeded);
    int[] createCount = {0};
    PairEntry got =
        table.getOrCreate(
            "a",
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
  void entryMatchesTrueWhenBothKeysEqual() {
    PairEntry entry = new PairEntry("a", 1, 100);
    assertTrue(entry.matches("a", 1));
  }

  @Test
  void entryMatchesFalseWhenKey1Differs() {
    PairEntry entry = new PairEntry("a", 1, 100);
    assertFalse(entry.matches("b", 1));
  }

  @Test
  void entryMatchesFalseWhenKey2Differs() {
    PairEntry entry = new PairEntry("a", 1, 100);
    assertFalse(entry.matches("a", 2));
  }

  @Test
  void keyAccessorsExposeConstructionKeys() {
    PairEntry entry = new PairEntry("a", 1, 100);
    assertEquals("a", entry.key1());
    assertEquals(1, entry.key2());
  }

  @Test
  void entryHashIsConsistentForSameKeys() {
    long h1 = Hashtable.D2.Entry.hash("x", 42);
    long h2 = Hashtable.D2.Entry.hash("x", 42);
    assertEquals(h1, h2);
  }

  @Test
  void entryHashDiffersForDifferentKeys() {
    long h1 = Hashtable.D2.Entry.hash("x", 1);
    long h2 = Hashtable.D2.Entry.hash("x", 2);
    assertFalse(h1 == h2);
  }

  @Test
  void removeReturnsNullForMissingKey() {
    Hashtable.D2<String, Integer, PairEntry> table = new Hashtable.D2<>(8);
    table.insert(new PairEntry("a", 1, 100));

    assertNull(table.remove("a", 2));
    assertNull(table.remove("z", 1));
    assertEquals(1, table.size());
  }

  @Test
  void clearEmptiesTable() {
    Hashtable.D2<String, Integer, PairEntry> table = new Hashtable.D2<>(8);
    table.insert(new PairEntry("a", 1, 100));
    table.insert(new PairEntry("b", 2, 200));
    assertEquals(2, table.size());

    table.clear();

    assertEquals(0, table.size());
    assertNull(table.get("a", 1));
    assertNull(table.get("b", 2));
  }

  private static final class PairEntry extends Hashtable.D2.Entry<String, Integer> {
    int value;

    PairEntry(String key1, Integer key2, int value) {
      super(key1, key2);
      this.value = value;
    }
  }
}
