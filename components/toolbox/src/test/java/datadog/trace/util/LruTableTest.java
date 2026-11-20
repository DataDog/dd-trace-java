package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LruTableTest {

  static final class Counter extends LruTable.D1.Entry<Object> {
    int count;

    Counter(final Object key) {
      super(key);
    }
  }

  static final class Pair extends LruTable.D2.Entry<String, Integer> {
    Pair(final String key1, final Integer key2) {
      super(key1, key2);
    }
  }

  static final class ObjectPair extends LruTable.D2.Entry<Object, Object> {
    ObjectPair(final Object key1, final Object key2) {
      super(key1, key2);
    }
  }

  /** A key whose hash code is fixed, so keys collide in one bucket chain. */
  static final class Colliding {
    final String name;

    Colliding(final String name) {
      this.name = name;
    }

    @Override
    public int hashCode() {
      return 7;
    }

    @Override
    public boolean equals(final Object o) {
      return o instanceof Colliding && ((Colliding) o).name.equals(name);
    }
  }

  @Test
  void evictsTheLeastRecentlyUsedEntryAndReturnsIt() {
    final LruTable.D1<Object, Counter> table = new LruTable.D1<>(3);
    final Counter a = new Counter("a");
    final Counter b = new Counter("b");
    final Counter c = new Counter("c");
    assertNull(table.insert(a));
    assertNull(table.insert(b));
    assertNull(table.insert(c));
    assertSame(a, table.eldest());

    assertSame(a, table.get("a")); // a becomes the most recent; b is now the eldest
    assertSame(b, table.insert(new Counter("d")));
    assertNull(table.peek("b"));
    assertEquals(3, table.size());
    assertEquals(keys(table), list("c", "a", "d"));
  }

  @Test
  void peekDoesNotChangeTheOrder() {
    final LruTable.D1<Object, Counter> table = new LruTable.D1<>(2);
    final Counter a = new Counter("a");
    table.insert(a);
    table.insert(new Counter("b"));
    assertSame(a, table.peek("a"));
    assertSame(a, table.insert(new Counter("c")));
  }

  @Test
  void removesAndClears() {
    final LruTable.D1<Object, Counter> table = new LruTable.D1<>(4);
    for (final String key : new String[] {"a", "b", "c"}) {
      table.insert(new Counter(key));
    }
    assertEquals("b", table.remove("b").key());
    assertNull(table.remove("b"));
    assertEquals(list("a", "c"), keys(table));
    assertEquals(2, table.size());

    table.clear();
    assertEquals(0, table.size());
    assertNull(table.eldest());
    assertNull(table.get("a"));
    final Counter again = new Counter("a");
    table.insert(again);
    assertSame(again, table.get("a"));
  }

  @Test
  void aTableWithoutRoomRemembersNothing() {
    final LruTable.D1<Object, Counter> table = new LruTable.D1<>(0);
    final Counter a = new Counter("a");
    assertSame(a, table.insert(a));
    assertEquals(0, table.size());
    assertNull(table.get("a"));
    assertThrows(IllegalArgumentException.class, () -> new LruTable.D1<>(-1));
  }

  @Test
  void handlesNullKeysAndCollidingKeys() {
    final LruTable.D1<Object, Counter> table = new LruTable.D1<>(8);
    final Counter none = new Counter(null);
    table.insert(none);
    assertSame(none, table.get(null));

    final List<Counter> colliding = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      final Counter counter = new Counter(new Colliding("k" + i));
      colliding.add(counter);
      table.insert(counter);
    }
    for (int i = 0; i < 5; i++) {
      assertSame(colliding.get(i), table.get(new Colliding("k" + i)));
    }
    // Remove from the middle of the chain, then check the rest are still found.
    assertSame(colliding.get(2), table.remove(new Colliding("k2")));
    for (int i = 0; i < 5; i++) {
      if (i != 2) {
        assertSame(colliding.get(i), table.peek(new Colliding("k" + i)));
      }
    }
  }

  @Test
  void growsWithItsEntriesUpToTheFixedTableSize() {
    final int max = 10_000;
    final LruTable.D2<String, Integer, Pair> table = new LruTable.D2<>(max);
    assertEquals(LruTable.INITIAL_BUCKETS, table.buckets.length);
    final List<Pair> pairs = new ArrayList<>();
    for (int i = 0; i < max; i++) {
      final Pair pair = new Pair("flag-" + (i % 7), i);
      pairs.add(pair);
      assertNull(table.insert(pair));
    }
    assertEquals(Hashtable.capacityFor(max), table.buckets.length);
    for (int i = 0; i < max; i++) {
      assertSame(pairs.get(i), table.peek("flag-" + (i % 7), i));
    }
    // Full: the next insert evicts the eldest, without growing.
    assertSame(pairs.get(0), table.insert(new Pair("flag-x", -1)));
    assertEquals(Hashtable.capacityFor(max), table.buckets.length);
    assertEquals(max, table.size());
  }

  @Test
  void aSmallBoundNeverAllocatesMoreThanItNeeds() {
    final LruTable.D1<Object, Counter> table = new LruTable.D1<>(3);
    assertTrue(table.buckets.length <= Hashtable.capacityFor(3));
    assertEquals(3, table.maxSize());
  }

  @Test
  void twoPartKeysMatchOnBothParts() {
    final LruTable.D2<String, Integer, Pair> table = new LruTable.D2<>(4);
    final Pair pair = new Pair("flag", 1);
    table.insert(pair);
    assertSame(pair, table.get("flag", 1));
    assertNull(table.get("flag", 2));
    assertNull(table.get("other", 1));
    assertSame(pair, table.remove("flag", 1));
    assertNull(table.remove("flag", 1));
  }

  @Test
  void twoPartKeysWithEqualHashesStillMatchOnBothParts() {
    final LruTable.D2<Object, Object, ObjectPair> table = new LruTable.D2<>(8);
    final Colliding a = new Colliding("a");
    final Colliding b = new Colliding("b");
    final ObjectPair ab = new ObjectPair(a, b);
    final ObjectPair ba = new ObjectPair(b, a);
    final ObjectPair aa = new ObjectPair(a, a);
    table.insert(ab);
    table.insert(ba);
    table.insert(aa);
    assertSame(ab, table.get(new Colliding("a"), new Colliding("b")));
    assertSame(ba, table.get(new Colliding("b"), new Colliding("a")));
    assertSame(aa, table.peek(new Colliding("a"), new Colliding("a")));
    assertNull(table.peek(new Colliding("b"), new Colliding("b")));
    assertSame(a, ab.key1());
    assertSame(b, ab.key2());
  }

  private static List<Object> keys(final LruTable.D1<Object, Counter> table) {
    final List<Object> keys = new ArrayList<>();
    table.forEach(entry -> keys.add(entry.key()));
    return keys;
  }

  private static List<Object> list(final Object... keys) {
    final List<Object> list = new ArrayList<>();
    for (final Object key : keys) {
      list.add(key);
    }
    return list;
  }
}
