package datadog.trace.util;

import static datadog.trace.util.LightStringMap.EmbeddingSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LightStringMapTest {

  // ============ Instance API ============

  @Nested
  class InstanceTests {

    @Test
    void getOnEmptyMapReturnsNull() {
      LightStringMap<Integer> map = new LightStringMap<>(LightStringMap.DEFAULT_CAPACITY);
      assertNull(map.get("absent"));
    }

    @Test
    void setThenGet() {
      LightStringMap<Integer> map = new LightStringMap<>(8);
      map.set("a", 1);
      map.set("b", 2);
      assertEquals(1, map.get("a"));
      assertEquals(2, map.get("b"));
      assertNull(map.get("c"));
    }

    @Test
    void setOverwritesExistingKey() {
      LightStringMap<Integer> map = new LightStringMap<>(8);
      map.set("a", 1);
      map.set("a", 42);
      assertEquals(42, map.get("a"));
    }

    @Test
    void removeMakesKeyAbsent() {
      LightStringMap<Integer> map = new LightStringMap<>(8);
      map.set("a", 1);
      map.set("b", 2);
      map.remove("a");
      assertNull(map.get("a"));
      assertEquals(2, map.get("b"));
    }

    @Test
    void containsKeyDistinguishesPresenceFromAbsence() {
      LightStringMap<Integer> map = new LightStringMap<>(8);
      assertFalse(map.containsKey("a"));
      map.set("a", 1);
      assertTrue(map.containsKey("a"));
      assertFalse(map.containsKey("b"));
      map.remove("a");
      assertFalse(map.containsKey("a"));
    }

    @Test
    void setRejectsNullValue() {
      LightStringMap<Integer> map = new LightStringMap<>(8);
      assertThrows(NullPointerException.class, () -> map.set("a", null));
      assertFalse(map.containsKey("a"));
    }

    @Test
    void growsAndPreservesAllEntries() {
      // initial capacity 2 forces several resizes as we insert well past it.
      LightStringMap<Integer> map = new LightStringMap<>(2);
      int n = 100;
      for (int i = 0; i < n; i++) {
        map.set("key-" + i, i);
      }
      for (int i = 0; i < n; i++) {
        assertEquals(i, map.get("key-" + i), "key-" + i);
      }
    }

    @Test
    void forEachVisitsEveryLiveEntry() {
      LightStringMap<Integer> map = new LightStringMap<>(4);
      for (int i = 0; i < 20; i++) {
        map.set("k" + i, i);
      }
      map.remove("k5");
      map.remove("k12");

      Map<String, Integer> seen = new HashMap<>();
      map.forEach(seen::put);

      assertEquals(18, seen.size());
      assertFalse(seen.containsKey("k5"));
      assertFalse(seen.containsKey("k12"));
      assertEquals(7, seen.get("k7"));
    }

    @Test
    void nonLiteralKeyResolvesViaEqualsFallback() {
      LightStringMap<Integer> map = new LightStringMap<>(8);
      map.set("hello", 1);
      // Distinct String instance with the same content -- must be found via the equals pass.
      String lookup = new String("hello");
      assertEquals(1, map.get(lookup));
    }

    @Test
    void removeThenReinsertSameKey() {
      LightStringMap<Integer> map = new LightStringMap<>(4);
      for (int i = 0; i < 4; i++) {
        map.set("k" + i, i);
      }
      map.remove("k1");
      assertNull(map.get("k1"));
      map.set("k1", 99);
      assertEquals(99, map.get("k1"));
    }
  }

  // ============ EmbeddingSupport spine ============

  @Nested
  class EmbeddingSupportTests {

    @Test
    void emptyMapProbes() {
      assertTrue(EmbeddingSupport.isDefinitelyEmpty(EmbeddingSupport.EMPTY_DATA));
      assertEquals(0, EmbeddingSupport.numSlots(null));
      assertEquals(0, EmbeddingSupport.size(null));
      assertNull(EmbeddingSupport.get(null, "x"));
      assertFalse(EmbeddingSupport.remove(null, "x"));
      assertFalse(EmbeddingSupport.containsKey(null, "x"));
      assertFalse(EmbeddingSupport.containsValue(null, "x"));
      assertEquals(EmbeddingSupport.NOT_FOUND, EmbeddingSupport.findSlot(null, "x"));
    }

    @Test
    void setGrowsFromNullAndReadsBack() {
      Object[] data = null;
      data = EmbeddingSupport.set(4, data, "a", "A");
      data = EmbeddingSupport.set(4, data, "b", "B");
      assertEquals("A", EmbeddingSupport.get(data, "a"));
      assertEquals("B", EmbeddingSupport.get(data, "b"));
      assertEquals(2, EmbeddingSupport.size(data));
    }

    @Test
    void defaultCapacitySetOverload() {
      Object[] data = EmbeddingSupport.set(null, "a", "A");
      assertEquals(LightStringMap.DEFAULT_CAPACITY, EmbeddingSupport.numSlots(data));
      assertEquals("A", EmbeddingSupport.get(data, "a"));
    }

    @Test
    void containsKeyAndContainsValue() {
      Object[] data = null;
      data = EmbeddingSupport.set(8, data, "a", "A");
      data = EmbeddingSupport.set(8, data, "b", "B");
      assertTrue(EmbeddingSupport.containsKey(data, "a"));
      assertFalse(EmbeddingSupport.containsKey(data, "z"));
      assertTrue(EmbeddingSupport.containsValue(data, "B"));
      assertFalse(EmbeddingSupport.containsValue(data, "Z"));
    }

    @Test
    void removeLeavesTombstoneButKeepsProbeChainIntact() {
      // Insert many keys into a small table, remove a middle one, ensure the rest still resolve.
      Object[] data = null;
      for (int i = 0; i < 16; i++) {
        data = EmbeddingSupport.set(2, data, "k" + i, i);
      }
      assertTrue(EmbeddingSupport.remove(data, "k8"));
      assertNull(EmbeddingSupport.get(data, "k8"));
      for (int i = 0; i < 16; i++) {
        if (i == 8) continue;
        assertEquals(i, (Object) EmbeddingSupport.get(data, "k" + i), "k" + i);
      }
      assertEquals(15, EmbeddingSupport.size(data));
    }

    @Test
    void expandDiscardsTombstones() {
      Object[] data = null;
      for (int i = 0; i < 4; i++) {
        data = EmbeddingSupport.set(4, data, "k" + i, i);
      }
      EmbeddingSupport.remove(data, "k0");
      EmbeddingSupport.remove(data, "k1");
      // A live tombstone exists now; expanding must purge them (size unchanged, no REMOVED slots).
      Object[] expanded = EmbeddingSupport.expandMapData(data);
      assertEquals(2, EmbeddingSupport.size(expanded));
      int numSlots = EmbeddingSupport.numSlots(expanded);
      for (int slot = 0; slot < numSlots; slot++) {
        assertFalse(
            EmbeddingSupport.isRemoved((String) expanded[slot]),
            "expanded table must not carry tombstones");
      }
      assertEquals(2, (Object) EmbeddingSupport.get(expanded, "k2"));
      assertEquals(3, (Object) EmbeddingSupport.get(expanded, "k3"));
    }

    @Test
    void copyIsIndependent() {
      Object[] data = EmbeddingSupport.set(4, null, "a", "A");
      Object[] copy = EmbeddingSupport.copy(data);
      EmbeddingSupport.set(4, copy, "b", "B");
      assertNull(EmbeddingSupport.get(data, "b"));
      assertEquals("B", EmbeddingSupport.get(copy, "b"));
      assertNull(EmbeddingSupport.copy(null));
    }

    @Test
    void clearNullsEverything() {
      Object[] data = EmbeddingSupport.set(4, null, "a", "A");
      EmbeddingSupport.clear(data);
      assertEquals(0, EmbeddingSupport.size(data));
      assertNull(EmbeddingSupport.get(data, "a"));
    }

    @Test
    void keyAtValueAtAndPresence() {
      Object[] data = EmbeddingSupport.set(8, null, "a", "A");
      int slot = EmbeddingSupport.findSlot(data, "a");
      assertTrue(EmbeddingSupport.isPresent(slot));
      assertEquals("a", EmbeddingSupport.keyAt(data, slot));
      assertEquals("A", EmbeddingSupport.valueAt(data, slot));
      assertEquals("A", EmbeddingSupport.prevValueAt(data, slot));
      // negative slot -> absent
      assertNull(EmbeddingSupport.keyAt(data, -1));
      assertFalse(EmbeddingSupport.isPresent(-1));
    }

    @Test
    void findSlotMissReturnsMinusOneNotSentinel() {
      Object[] data = EmbeddingSupport.set(8, null, "a", "A");
      assertEquals(-1, EmbeddingSupport.findSlot(data, "absent"));
    }

    @Test
    void removeAtAndGetAndRemoveAt() {
      Object[] data = EmbeddingSupport.set(8, null, "a", "A");
      int slot = EmbeddingSupport.findSlot(data, "a");
      Object prev = EmbeddingSupport.getAndRemoveAt(data, slot);
      assertEquals("A", prev);
      assertNull(EmbeddingSupport.get(data, "a"));

      Object[] data2 = EmbeddingSupport.set(8, null, "b", "B");
      int slot2 = EmbeddingSupport.findSlot(data2, "b");
      EmbeddingSupport.removeAt(data2, slot2);
      assertNull(EmbeddingSupport.get(data2, "b"));
    }

    @Test
    void insertAtUsingFoundInsertionSlot() {
      Object[] data = EmbeddingSupport.set(8, null, "a", "A");
      int insertionSlot = EmbeddingSupport.findInsertionSlot(data, "b");
      data = EmbeddingSupport.insertAt(8, data, insertionSlot, "b", "B");
      assertEquals("A", EmbeddingSupport.get(data, "a"));
      assertEquals("B", EmbeddingSupport.get(data, "b"));
    }

    @Test
    void insertAtFromNullBuildsNewMap() {
      Object[] data = EmbeddingSupport.insertAt(4, null, EmbeddingSupport.NO_SPACE, "a", "A");
      assertEquals("A", EmbeddingSupport.get(data, "a"));
    }

    @Test
    void setAllMergesSourceIntoDest() {
      Object[] dest = null;
      dest = EmbeddingSupport.set(8, dest, "a", "A");
      dest = EmbeddingSupport.set(8, dest, "b", "B");

      Object[] src = null;
      src = EmbeddingSupport.set(8, src, "c", "C");
      src = EmbeddingSupport.set(8, src, "d", "D");

      dest = EmbeddingSupport.setAll(dest, src);
      assertEquals(4, EmbeddingSupport.size(dest));
      assertEquals("A", EmbeddingSupport.get(dest, "a"));
      assertEquals("C", EmbeddingSupport.get(dest, "c"));
      assertEquals("D", EmbeddingSupport.get(dest, "d"));
    }

    @Test
    void setAllFromNullDestClonesSource() {
      Object[] src = EmbeddingSupport.set(8, null, "c", "C");
      Object[] dest = EmbeddingSupport.setAll(null, src);
      assertEquals("C", EmbeddingSupport.get(dest, "c"));
      // clone -> independent from src
      EmbeddingSupport.set(8, dest, "d", "D");
      assertNull(EmbeddingSupport.get(src, "d"));
    }

    @Test
    void forEachStaticVisitsLiveEntries() {
      Object[] data = null;
      for (int i = 0; i < 6; i++) {
        data = EmbeddingSupport.set(4, data, "k" + i, i);
      }
      EmbeddingSupport.remove(data, "k3");

      Map<String, Object> seen = new HashMap<>();
      EmbeddingSupport.forEach(data, seen::put);
      assertEquals(5, seen.size());
      assertFalse(seen.containsKey("k3"));
    }

    @Test
    void forEachStaticWithContext() {
      Object[] data = null;
      for (int i = 0; i < 4; i++) {
        data = EmbeddingSupport.set(4, data, "k" + i, i);
      }
      Map<String, Object> ctx = new HashMap<>();
      EmbeddingSupport.forEach(data, ctx, (c, k, v) -> c.put(k, v));
      assertEquals(4, ctx.size());
      assertEquals(2, ctx.get("k2"));
    }

    @Test
    void toInternalStringRendersLiveAndRemoved() {
      Object[] data = EmbeddingSupport.set(4, null, "a", "A");
      data = EmbeddingSupport.set(4, data, "b", "B");
      EmbeddingSupport.remove(data, "a");
      String rendered = EmbeddingSupport.toInternalString(data);
      assertTrue(rendered.contains("b:B"));
      assertTrue(rendered.contains("--REMOVED--"));
    }
  }

  // ============ Slot math ============

  @Nested
  class SlotMathTests {

    @Test
    void roundUpToPow2() {
      assertEquals(1, EmbeddingSupport.roundUpToPow2(0));
      assertEquals(1, EmbeddingSupport.roundUpToPow2(1));
      assertEquals(2, EmbeddingSupport.roundUpToPow2(2));
      assertEquals(4, EmbeddingSupport.roundUpToPow2(3));
      assertEquals(8, EmbeddingSupport.roundUpToPow2(5));
      assertEquals(16, EmbeddingSupport.roundUpToPow2(16));
    }

    @Test
    void preferredSlotIsBoundedByNumSlots() {
      int numSlots = 16;
      for (int hash : new int[] {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 123456}) {
        int slot = EmbeddingSupport.preferredSlot(numSlots, hash);
        assertTrue(slot >= 0 && slot < numSlots, "slot out of range for hash " + hash);
      }
    }

    @Test
    void isRemovedOnlyMatchesTheSentinel() {
      assertTrue(EmbeddingSupport.isRemoved(EmbeddingSupport.REMOVED));
      assertFalse(EmbeddingSupport.isRemoved("removed"));
      assertFalse(EmbeddingSupport.isRemoved(new String("REMOVED")));
    }
  }
}
