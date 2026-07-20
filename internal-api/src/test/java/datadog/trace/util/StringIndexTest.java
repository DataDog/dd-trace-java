package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.StringIndex.Data;
import datadog.trace.util.StringIndex.EmbeddingSupport;
import org.junit.jupiter.api.Test;

class StringIndexTest {

  @Test
  void hash_spread_and_zeroSentinel() {
    // "".hashCode() == 0 -> remapped to the non-zero sentinel so 0 can mean "empty slot"
    assertEquals(0xDD06, EmbeddingSupport.hash(""));

    int raw = "foo".hashCode();
    assertEquals(raw ^ (raw >>> 16), EmbeddingSupport.hash("foo"));
    assertNotEquals(0, EmbeddingSupport.hash("foo"));
  }

  @Test
  void capacityFor_isPow2_andAtLeastDoubled() {
    assertEquals(2, EmbeddingSupport.capacityFor(0)); // empty set -> minimal table
    assertEquals(2, EmbeddingSupport.capacityFor(1)); // >= 2x, smallest power of two
    assertEquals(8, EmbeddingSupport.capacityFor(3)); // ceil(3/0.5)=6 -> 8
    assertEquals(8, EmbeddingSupport.capacityFor(4)); // ceil(4/0.5)=8 -> 8 (was 16: tightened)
    assertEquals(64, EmbeddingSupport.capacityFor(16, EmbeddingSupport.LOW_LOAD_FACTOR)); // 4x
  }

  @Test
  void capacityFor_rejectsBadArgs() {
    assertThrows(IllegalArgumentException.class, () -> EmbeddingSupport.capacityFor(-1));
    assertThrows(IllegalArgumentException.class, () -> EmbeddingSupport.capacityFor(4, 0f));
    assertThrows(IllegalArgumentException.class, () -> EmbeddingSupport.capacityFor(4, 1f));
  }

  @Test
  void instance_contains_internedAndCopy_andMiss() {
    StringIndex set = StringIndex.of("foo", "bar", "baz");

    assertEquals(8, set.numSlots()); // 3 names -> capacityFor(3) == 8

    assertTrue(set.contains("foo")); // interned literal -> == fast path in eq
    assertTrue(set.contains(new String("bar"))); // non-interned -> .equals path
    assertFalse(set.contains("nope"));

    assertTrue(set.indexOf("baz") >= 0);
    assertEquals(-1, set.indexOf("nope"));
  }

  @Test
  void support_create_then_indexOf() {
    Data d = EmbeddingSupport.create("x", "y");

    int slot = EmbeddingSupport.indexOf(d.hashes, d.names, "x"); // 3-arg overload computes the hash
    assertTrue(slot >= 0);
    assertEquals("x", d.names[slot]);

    assertEquals(-1, EmbeddingSupport.indexOf(d.hashes, d.names, "q"));
  }

  /** Controlled hashes force collision, linear-probe wraparound, and the already-present path. */
  @Test
  void put_and_indexOf_collisionAndWraparound() {
    int[] hashes = new int[4]; // mask = 3
    String[] names = new String[4];

    assertEquals(3, EmbeddingSupport.put(hashes, names, "a", 7)); // 7 & 3 == 3
    assertEquals(
        0, EmbeddingSupport.put(hashes, names, "b", 7)); // collides at 3, probes (3+1)&3 == 0
    assertEquals(
        3, EmbeddingSupport.put(hashes, names, "a", 7)); // already present -> existing slot

    assertEquals(3, EmbeddingSupport.indexOf(hashes, names, "a", 7)); // direct hit
    assertEquals(
        0, EmbeddingSupport.indexOf(hashes, names, "b", 7)); // hit after collision + wraparound
    assertEquals(
        -1,
        EmbeddingSupport.indexOf(hashes, names, "c", 7)); // miss after probing 3 -> 0 -> 1(empty)
    assertEquals(
        -1, EmbeddingSupport.indexOf(hashes, names, "z", 6)); // 6 & 3 == 2, empty -> immediate miss
  }

  @Test
  void put_throwsWhenFull() {
    int[] hashes = new int[2]; // mask = 1
    String[] names = new String[2];

    EmbeddingSupport.put(hashes, names, "a", 4); // 4 & 1 == 0
    EmbeddingSupport.put(hashes, names, "b", 5); // 5 & 1 == 1

    // both slots occupied, no match -> probe exhausts -> throw
    assertThrows(IllegalStateException.class, () -> EmbeddingSupport.put(hashes, names, "c", 6));
  }

  /** The documented usage: build a StringIndex, attach a parallel payload indexed by slot. */
  @Test
  void parallelPayloadBySlot() {
    String[] names = {"a", "b", "c"};
    Data d = EmbeddingSupport.create(names);

    long[] ids = new long[d.names.length];
    for (int j = 0; j < names.length; j++) {
      ids[EmbeddingSupport.indexOf(d.hashes, d.names, names[j])] = j + 1L;
    }

    assertEquals(1L, ids[EmbeddingSupport.indexOf(d.hashes, d.names, "a")]);
    assertEquals(2L, ids[EmbeddingSupport.indexOf(d.hashes, d.names, "b")]);
    assertEquals(3L, ids[EmbeddingSupport.indexOf(d.hashes, d.names, "c")]);
  }

  @Test
  void mapIntValues_slotAligned_andLookup() {
    StringIndex idx = StringIndex.of("a", "b", "c");
    // 1-based ids; 0 stays the empty-slot / not-found sentinel.
    int[] ids = idx.mapIntValues(s -> s.charAt(0) - 'a' + 1);
    assertEquals(idx.numSlots(), ids.length); // sized to the table, not the name count

    assertEquals(1, idx.lookup(ids, "a"));
    assertEquals(2, idx.lookup(ids, "b"));
    assertEquals(3, idx.lookup(ids, "c"));
    assertEquals(0, idx.lookup(ids, "z")); // miss -> 0
    assertEquals(-1, idx.lookupOrDefault(ids, "z", -1)); // miss -> supplied default
  }

  @Test
  void mapLongValues_slotAligned_andLookup() {
    Data d = EmbeddingSupport.create("a", "b", "c");
    long[] vals = EmbeddingSupport.mapLongValues(d.names, s -> s.charAt(0) - 'a' + 1L);

    assertEquals(1L, EmbeddingSupport.lookup(d.hashes, d.names, vals, "a"));
    assertEquals(3L, EmbeddingSupport.lookup(d.hashes, d.names, vals, "c"));
    assertEquals(0L, EmbeddingSupport.lookup(d.hashes, d.names, vals, "z")); // miss -> 0
    assertEquals(-1L, EmbeddingSupport.lookupOrDefault(d.hashes, d.names, vals, "z", -1L));
  }

  @Test
  void mapValues_objects_typedArray_andLookup() {
    StringIndex idx = StringIndex.of("a", "bb", "ccc");
    Integer[] lengths = idx.mapValues(Integer.class, String::length);

    // Class<T> drives a real Integer[], not an Object[].
    assertEquals(Integer[].class, lengths.getClass());

    assertEquals(Integer.valueOf(1), idx.lookup(lengths, "a"));
    assertEquals(Integer.valueOf(3), idx.lookup(lengths, "ccc"));
    assertNull(idx.lookup(lengths, "z")); // miss -> null
    assertEquals(Integer.valueOf(-1), idx.lookupOrDefault(lengths, "z", -1));
  }

  @Test
  void support_mapValues_objects_sizedToSlots_emptyStayNull() {
    Data d = EmbeddingSupport.create("a", "b", "c");
    String[] tagged = EmbeddingSupport.mapValues(d.names, String.class, s -> s + "!");

    assertEquals(d.names.length, tagged.length); // sized to the table
    int nonNull = 0;
    for (String s : tagged) {
      if (s != null) {
        nonNull++;
      }
    }
    assertEquals(3, nonNull); // only the placed names map; unfilled slots stay null

    assertEquals("a!", EmbeddingSupport.lookup(d.hashes, d.names, tagged, "a"));
    assertEquals("dflt", EmbeddingSupport.lookupOrDefault(d.hashes, d.names, tagged, "z", "dflt"));
  }

  @Test
  void instance_lookup_delegatesToSupportArrays() {
    StringIndex idx = StringIndex.of("x", "y");
    int[] ids = idx.mapIntValues(s -> "x".equals(s) ? 7 : 9);

    assertEquals(7, idx.lookup(ids, "x"));
    assertEquals(9, idx.lookup(ids, "y"));
    assertEquals(0, idx.lookup(ids, "missing"));
    assertEquals(42, idx.lookupOrDefault(ids, "missing", 42));
  }

  @Test
  void instance_longValues_mapAndLookup() {
    StringIndex idx = StringIndex.of("a", "b", "c");
    long[] vals = idx.mapLongValues(s -> s.charAt(0) - 'a' + 1L);
    assertEquals(idx.numSlots(), vals.length); // sized to the table, not the name count

    assertEquals(1L, idx.lookup(vals, "a"));
    assertEquals(3L, idx.lookup(vals, "c"));
    assertEquals(0L, idx.lookup(vals, "z")); // miss -> 0
    assertEquals(2L, idx.lookupOrDefault(vals, "b", -1L)); // hit
    assertEquals(-1L, idx.lookupOrDefault(vals, "z", -1L)); // miss -> supplied default
  }

  @Test
  void support_numSlots_matchesTableSize() {
    Data d = EmbeddingSupport.create("a", "b", "c");
    assertEquals(d.hashes.length, EmbeddingSupport.numSlots(d.hashes));
  }
}
