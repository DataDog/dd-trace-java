package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  private static final class PairEntry extends Hashtable.D2.Entry<String, Integer> {
    int value;

    PairEntry(String key1, Integer key2, int value) {
      super(key1, key2);
      this.value = value;
    }
  }
}
