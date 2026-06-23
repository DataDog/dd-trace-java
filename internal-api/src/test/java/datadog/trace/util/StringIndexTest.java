package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.StringIndex.Data;
import datadog.trace.util.StringIndex.Support;
import org.junit.jupiter.api.Test;

class StringIndexTest {

  @Test
  void hash_spread_and_zeroSentinel() {
    // "".hashCode() == 0 -> remapped to the non-zero sentinel so 0 can mean "empty slot"
    assertEquals(0xDD06, Support.hash(""));

    int raw = "foo".hashCode();
    assertEquals(raw ^ (raw >>> 16), Support.hash("foo"));
    assertNotEquals(0, Support.hash("foo"));
  }

  @Test
  void tableSizeFor_isPow2_andOversized() {
    assertEquals(2, Support.tableSizeFor(0));
    assertEquals(4, Support.tableSizeFor(1));
    assertEquals(8, Support.tableSizeFor(3));
    assertEquals(16, Support.tableSizeFor(4));
  }

  @Test
  void instance_contains_internedAndCopy_andMiss() {
    StringIndex set = StringIndex.of("foo", "bar", "baz");

    assertEquals(8, set.slots()); // 3 names -> tableSizeFor(3) == 8

    assertTrue(set.contains("foo")); // interned literal -> == fast path in eq
    assertTrue(set.contains(new String("bar"))); // non-interned -> .equals path
    assertFalse(set.contains("nope"));

    assertTrue(set.indexOf("baz") >= 0);
    assertEquals(-1, set.indexOf("nope"));
  }

  @Test
  void support_create_then_indexOf() {
    Data d = Support.create("x", "y");

    int slot = Support.indexOf(d.hashes, d.names, "x"); // 3-arg overload computes the hash
    assertTrue(slot >= 0);
    assertEquals("x", d.names[slot]);

    assertEquals(-1, Support.indexOf(d.hashes, d.names, "q"));
  }

  /** Controlled hashes force collision, linear-probe wraparound, and the already-present path. */
  @Test
  void put_and_indexOf_collisionAndWraparound() {
    int[] hashes = new int[4]; // mask = 3
    String[] names = new String[4];

    assertEquals(3, Support.put(hashes, names, "a", 7)); // 7 & 3 == 3
    assertEquals(0, Support.put(hashes, names, "b", 7)); // collides at 3, probes (3+1)&3 == 0
    assertEquals(3, Support.put(hashes, names, "a", 7)); // already present -> existing slot

    assertEquals(3, Support.indexOf(hashes, names, "a", 7)); // direct hit
    assertEquals(0, Support.indexOf(hashes, names, "b", 7)); // hit after collision + wraparound
    assertEquals(
        -1, Support.indexOf(hashes, names, "c", 7)); // miss after probing 3 -> 0 -> 1(empty)
    assertEquals(-1, Support.indexOf(hashes, names, "z", 6)); // 6 & 3 == 2, empty -> immediate miss
  }

  @Test
  void put_throwsWhenFull() {
    int[] hashes = new int[2]; // mask = 1
    String[] names = new String[2];

    Support.put(hashes, names, "a", 4); // 4 & 1 == 0
    Support.put(hashes, names, "b", 5); // 5 & 1 == 1

    // both slots occupied, no match -> probe exhausts -> throw
    assertThrows(IllegalStateException.class, () -> Support.put(hashes, names, "c", 6));
  }

  /** The documented usage: build a StringIndex, attach a parallel payload indexed by slot. */
  @Test
  void parallelPayloadBySlot() {
    String[] names = {"a", "b", "c"};
    Data d = Support.create(names);

    long[] ids = new long[d.names.length];
    for (int j = 0; j < names.length; j++) {
      ids[Support.indexOf(d.hashes, d.names, names[j])] = j + 1L;
    }

    assertEquals(1L, ids[Support.indexOf(d.hashes, d.names, "a")]);
    assertEquals(2L, ids[Support.indexOf(d.hashes, d.names, "b")]);
    assertEquals(3L, ids[Support.indexOf(d.hashes, d.names, "c")]);
  }
}
