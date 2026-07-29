package datadog.trace.util;

import static datadog.trace.util.LightStringMap.EmbeddingSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LightStringMapTest {

  // ============ Instance API ============

  @Nested
  class InstanceTests {

    @Test
    void getOnEmptyMapReturnsNull() {
      LightStringMap<Integer> map = LightStringMap.create(LightStringMap.DEFAULT_CAPACITY);
      assertNull(map.get("absent"));
    }

    @Test
    void setThenGet() {
      LightStringMap<Integer> map = LightStringMap.create(8);
      map.set("a", 1);
      map.set("b", 2);
      assertEquals(1, map.get("a"));
      assertEquals(2, map.get("b"));
      assertNull(map.get("c"));
    }

    @Test
    void setOverwritesExistingKey() {
      LightStringMap<Integer> map = LightStringMap.create(8);
      map.set("a", 1);
      map.set("a", 42);
      assertEquals(42, map.get("a"));
    }

    @Test
    void removeMakesKeyAbsent() {
      LightStringMap<Integer> map = LightStringMap.create(8);
      map.set("a", 1);
      map.set("b", 2);
      map.remove("a");
      assertNull(map.get("a"));
      assertEquals(2, map.get("b"));
    }

    @Test
    void containsKeyDistinguishesPresenceFromAbsence() {
      LightStringMap<Integer> map = LightStringMap.create(8);
      assertFalse(map.containsKey("a"));
      map.set("a", 1);
      assertTrue(map.containsKey("a"));
      assertFalse(map.containsKey("b"));
      map.remove("a");
      assertFalse(map.containsKey("a"));
    }

    @Test
    void setRejectsNullValue() {
      LightStringMap<Integer> map = LightStringMap.create(8);
      assertThrows(NullPointerException.class, () -> map.set("a", null));
      assertFalse(map.containsKey("a"));
    }

    @Test
    void sizeTracksLiveEntries() {
      LightStringMap<Integer> map = LightStringMap.create(8);
      assertEquals(0, map.size());
      map.set("a", 1);
      map.set("b", 2);
      assertEquals(2, map.size());
      map.set("a", 11); // overwrite does not change size
      assertEquals(2, map.size());
      map.remove("a");
      assertEquals(1, map.size());
      map.remove("missing"); // no-op does not change size
      assertEquals(1, map.size());
    }

    @Test
    void tinyMapGrowsOnlyWhenPhysicallyFull() {
      // The probe-bound grow trigger (MAX_PROBES == 8) cannot fire on an 8-slot table -- a probe
      // can never travel 8 slots there -- so a seed-8 map fills all 8 slots before it grows,
      // exactly as it did before the trigger existed. This is the property that keeps the tiny,
      // miss-dominated consumer (springweb6 localAttributes) behaviorally unchanged.
      LightStringMap<Integer> map = LightStringMap.create(8);
      for (int i = 0; i < 8; i++) {
        map.set("k" + i, i);
      }
      assertEquals(
          8, EmbeddingSupport.numSlots(map.dataForTesting()), "fills to full before growing");
      map.set("k8", 8);
      assertEquals(16, EmbeddingSupport.numSlots(map.dataForTesting()), "9th key forces the grow");
      assertEquals(9, map.size());
      for (int i = 0; i < 9; i++) {
        assertEquals(i, map.get("k" + i));
      }
    }

    @Test
    void clusteredKeysGrowEarlierThanWellSpreadKeys() {
      // The whole point of the probe bound: it responds to local clustering that a load factor is
      // blind to. The same number of keys, all colliding onto one home slot, must drive the table
      // strictly larger than a well-spread set would -- the trigger is firing on long probe chains
      // well before the table is anywhere near full. Both sets stay fully retrievable.
      int count = 32;

      LightStringMap<Integer> spread = LightStringMap.create(8);
      for (int i = 0; i < count; i++) {
        spread.set("spread." + i, i);
      }

      LightStringMap<Integer> clustered = LightStringMap.create(8);
      List<String> colliding = collidingKeys(count);
      for (int i = 0; i < count; i++) {
        clustered.set(colliding.get(i), i);
      }

      int spreadSlots = EmbeddingSupport.numSlots(spread.dataForTesting());
      int clusteredSlots = EmbeddingSupport.numSlots(clustered.dataForTesting());
      assertTrue(
          clusteredSlots > spreadSlots,
          "clustered keys should over-grow (" + clusteredSlots + " vs spread " + spreadSlots + ")");

      for (int i = 0; i < count; i++) {
        assertEquals(i, spread.get("spread." + i));
        assertEquals(i, clustered.get(colliding.get(i)));
      }
    }

    @Test
    void growsAndPreservesAllEntries() {
      // initial capacity 2 forces several resizes as we insert well past it.
      LightStringMap<Integer> map = LightStringMap.create(2);
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
      LightStringMap<Integer> map = LightStringMap.create(4);
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
      LightStringMap<Integer> map = LightStringMap.create(8);
      map.set("hello", 1);
      // Distinct String instance with the same content -- must be found via the equals pass.
      String lookup = new String("hello");
      assertEquals(1, map.get(lookup));
    }

    // Strings whose home slot collides in a large reference table (2^16 slots), and therefore in
    // every smaller power-of-two table the map passes through while growing -- so they pile onto a
    // single probe chain no matter how the map is sized here.
    private List<String> collidingKeys(int count) {
      int reference = 1 << 16;
      int target = EmbeddingSupport.preferredSlot(reference, "anchor".hashCode());
      List<String> keys = new ArrayList<>(count);
      for (int i = 0; keys.size() < count; i++) {
        String candidate = "collide." + i;
        if (EmbeddingSupport.preferredSlot(reference, candidate.hashCode()) == target) {
          keys.add(candidate);
        }
      }
      return keys;
    }

    @Test
    void removeThenReinsertSameKey() {
      LightStringMap<Integer> map = LightStringMap.create(4);
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

  @Nested
  class SizingHintTests {

    @Test
    void freshHintSeedsAtDefault() {
      LightStringMap.SizingHint hint = LightStringMap.sizingHint();
      assertEquals(LightStringMap.DEFAULT_HINT_SLOTS, hint.currentSeedSlots());
    }

    @Test
    void hintSeedsAFreshMapAtItsLearnedCapacity() {
      LightStringMap.SizingHint hint = LightStringMap.sizingHint();
      LightStringMap<Integer> map = LightStringMap.create(hint);
      // A hint-seeded map allocates its backing array lazily, sized to the hint.
      map.set("a", 1);
      Object[] data = map.dataForTesting();
      assertEquals(LightStringMap.DEFAULT_HINT_SLOTS, EmbeddingSupport.numSlots(data));
    }

    @Test
    void growthRaisesSeedWithOneClassOfHeadroom() {
      LightStringMap.SizingHint hint = LightStringMap.sizingHint();
      // Fill a hint-seeded map past its seed so it grows; the hint should learn the new size
      // PLUS one power-of-two class of headroom (so the steady-state load factor stays <= 0.5).
      LightStringMap<Integer> map = LightStringMap.create(hint);
      for (int i = 0; i < LightStringMap.DEFAULT_HINT_SLOTS + 1; i++) {
        map.set("k" + i, i);
      }
      int grownSlots = EmbeddingSupport.numSlots(map.dataForTesting());
      assertEquals(grownSlots * 2, hint.currentSeedSlots());
    }

    @Test
    void seedIsMonotonicMaxAcrossMaps() {
      LightStringMap.SizingHint hint = LightStringMap.sizingHint();
      // A big map ratchets the hint up.
      LightStringMap<Integer> big = LightStringMap.create(hint);
      for (int i = 0; i < 20; i++) {
        big.set("k" + i, i);
      }
      int learned = hint.currentSeedSlots();
      assertTrue(learned > LightStringMap.DEFAULT_HINT_SLOTS);
      // A subsequent tiny map does not lower the learned seed.
      LightStringMap<Integer> small = LightStringMap.create(hint);
      small.set("a", 1);
      assertEquals(learned, hint.currentSeedSlots());
    }

    @Test
    void decayStepsSeedDownAfterInterval() {
      LightStringMap.SizingHint hint = LightStringMap.sizingHint();
      // Ratchet the hint above the default so a step-down is observable.
      LightStringMap<Integer> big = LightStringMap.create(hint);
      for (int i = 0; i < 20; i++) {
        big.set("k" + i, i);
      }
      int learned = hint.currentSeedSlots();
      // One full decay interval of constructions steps the seed down exactly one class.
      for (int i = 0; i < LightStringMap.DECAY_INTERVAL; i++) {
        LightStringMap.<Integer>create(hint);
      }
      assertEquals(learned / 2, hint.currentSeedSlots());
    }

    @Test
    void decayFloorsAtMinimum() {
      LightStringMap.SizingHint hint = LightStringMap.sizingHint();
      // Enough decay intervals to drive an un-ratcheted hint to the floor and hold there.
      int intervals = 32;
      for (int i = 0; i < intervals * LightStringMap.DECAY_INTERVAL; i++) {
        LightStringMap.<Integer>create(hint);
      }
      assertEquals(LightStringMap.MIN_HINT_SLOTS, hint.currentSeedSlots());
    }

    @Test
    void seedIsCappedAtMax() {
      LightStringMap.SizingHint hint = LightStringMap.sizingHint();
      // A very large map cannot push the learned seed past the pre-provisioning ceiling.
      LightStringMap<Integer> big = LightStringMap.create(hint);
      for (int i = 0; i < LightStringMap.MAX_HINT_SLOTS * 4; i++) {
        big.set("k" + i, i);
      }
      assertTrue(hint.currentSeedSlots() <= LightStringMap.MAX_HINT_SLOTS);
      assertEquals(LightStringMap.MAX_HINT_SLOTS, hint.currentSeedSlots());
    }

    @Test
    void oneDecayStepStaysSafeThenSecondDecayRepins() {
      LightStringMap.SizingHint hint = LightStringMap.sizingHint();
      // Learn a large size. With one class of headroom, `learned` is 2x the array the workload
      // physically grew into.
      LightStringMap<Integer> big = LightStringMap.create(hint);
      for (int i = 0; i < 20; i++) {
        big.set("k" + i, i);
      }
      int learned = hint.currentSeedSlots();

      // First decay lands the seed exactly on the physical high-water: the same workload now fits
      // without regrowing, so the seed holds (the headroom step-down is "free").
      for (int i = 0; i < LightStringMap.DECAY_INTERVAL; i++) {
        LightStringMap.<Integer>create(hint);
      }
      assertEquals(learned / 2, hint.currentSeedSlots());
      LightStringMap<Integer> stillFits = LightStringMap.create(hint);
      for (int i = 0; i < 20; i++) {
        stillFits.set("k" + i, i);
      }
      assertEquals(learned / 2, hint.currentSeedSlots());

      // Second decay probes below the need: the workload now regrows and snaps the seed back up.
      for (int i = 0; i < LightStringMap.DECAY_INTERVAL; i++) {
        LightStringMap.<Integer>create(hint);
      }
      assertEquals(learned / 4, hint.currentSeedSlots());
      LightStringMap<Integer> recovered = LightStringMap.create(hint);
      for (int i = 0; i < 20; i++) {
        recovered.set("k" + i, i);
      }
      assertEquals(learned, hint.currentSeedSlots());
    }

    @Test
    void hintSeededMapStoresAndReadsBackCorrectly() {
      LightStringMap.SizingHint hint = LightStringMap.sizingHint();
      LightStringMap<Integer> map = LightStringMap.create(hint);
      for (int i = 0; i < 50; i++) {
        map.set("k" + i, i);
      }
      for (int i = 0; i < 50; i++) {
        assertEquals(i, map.get("k" + i));
      }
    }
  }

  // ============ maxCapacity hard cap + boolean set() ============

  @Nested
  class CapTests {

    @Test
    void uncappedMapAlwaysReturnsTrueFromSet() {
      // The plain capacity constructor is uncapped: set stores unconditionally and never rejects,
      // even well past the initial capacity.
      LightStringMap<Integer> map = LightStringMap.create(2);
      for (int i = 0; i < 100; i++) {
        assertTrue(map.set("k" + i, i), "uncapped set should always store");
      }
      assertEquals(100, map.size());
    }

    @Test
    void hintWithoutMaxCapacityIsUncapped() {
      // buildSizingHint() with no maxCapacity behaves like the zero-config sizingHint(): no cap, so
      // set always stores.
      LightStringMap.SizingHint hint = LightStringMap.buildSizingHint().build();
      LightStringMap<Integer> map = LightStringMap.create(hint);
      for (int i = 0; i < 100; i++) {
        assertTrue(map.set("k" + i, i));
      }
      assertEquals(100, map.size());
    }

    @Test
    void cappedSetStoresUntilPhysicallyFullThenRejects() {
      // maxCapacity(8) bounds the table at 8 slots. Eight distinct keys fill every slot (a
      // 8-slot table never trips the probe bound, so it fills before it would grow); a ninth,
      // genuinely new key cannot grow past the cap, so set rejects it and leaves the map unchanged.
      LightStringMap.SizingHint hint = LightStringMap.buildSizingHint().maxCapacity(8).build();
      LightStringMap<Integer> map = LightStringMap.create(hint);
      for (int i = 0; i < 8; i++) {
        assertTrue(map.set("k" + i, i), "slot " + i + " should store");
      }
      assertEquals(8, EmbeddingSupport.numSlots(map.dataForTesting()), "capped at 8 slots");
      assertEquals(8, map.size());

      assertFalse(map.set("overflow", 99), "new key past the cap should be rejected");
      assertEquals(8, map.size(), "rejected set must not change the map");
      assertNull(map.get("overflow"));
      // Every prior entry is still readable.
      for (int i = 0; i < 8; i++) {
        assertEquals(i, map.get("k" + i));
      }
    }

    @Test
    void cappedSetOverwritesExistingKeyEvenWhenFull() {
      // Rejection only applies to a genuinely new key. Overwriting a present key when the table is
      // full at the cap still succeeds -- no new slot is needed.
      LightStringMap.SizingHint hint = LightStringMap.buildSizingHint().maxCapacity(8).build();
      LightStringMap<Integer> map = LightStringMap.create(hint);
      for (int i = 0; i < 8; i++) {
        map.set("k" + i, i);
      }
      assertTrue(map.set("k3", 300), "overwrite of a present key should succeed when full");
      assertEquals(300, map.get("k3"));
      assertEquals(8, map.size());
    }

    @Test
    void cappedMapGrowsUpToTheCap() {
      // A cap does not pin the seed size: a map started small still grows through the
      // power-of-two classes up to the cap, retaining every entry along the way.
      LightStringMap.SizingHint hint =
          LightStringMap.buildSizingHint().initCapacity(2).maxCapacity(16).build();
      LightStringMap<Integer> map = LightStringMap.create(hint);
      for (int i = 0; i < 16; i++) {
        assertTrue(map.set("k" + i, i), "slot " + i + " should store below the cap");
      }
      assertEquals(16, EmbeddingSupport.numSlots(map.dataForTesting()));
      assertEquals(16, map.size());
      assertFalse(map.set("k16", 16), "the 17th key exceeds the 16-slot cap");
      for (int i = 0; i < 16; i++) {
        assertEquals(i, map.get("k" + i));
      }
    }

    @Test
    void afterRejectionRemovingThenReinsertingSucceeds() {
      // A rejection is non-fatal: freeing a slot (remove) makes room again, so a subsequent set of
      // a
      // new key succeeds via the reclaimed tombstone.
      LightStringMap.SizingHint hint = LightStringMap.buildSizingHint().maxCapacity(8).build();
      LightStringMap<Integer> map = LightStringMap.create(hint);
      for (int i = 0; i < 8; i++) {
        map.set("k" + i, i);
      }
      assertFalse(map.set("late", 99));
      map.remove("k0");
      assertTrue(map.set("late", 99), "a freed slot admits a new key");
      assertEquals(99, map.get("late"));
    }

    @Test
    void maxCapacityRoundsUpToPowerOfTwo() {
      // A non-power-of-two cap rounds up: maxCapacity(5) becomes an 8-slot cap.
      LightStringMap.SizingHint hint = LightStringMap.buildSizingHint().maxCapacity(5).build();
      LightStringMap<Integer> map = LightStringMap.create(hint);
      for (int i = 0; i < 8; i++) {
        assertTrue(map.set("k" + i, i));
      }
      assertFalse(map.set("k8", 8), "cap of 5 rounds up to 8 slots");
    }

    @Test
    void buildThrowsWhenInitCapacityExceedsMaxCapacity() {
      assertThrows(
          IllegalArgumentException.class,
          () -> LightStringMap.buildSizingHint().initCapacity(16).maxCapacity(8).build());
    }

    @Test
    void cappedHintNeverSeedsAMapLargerThanTheCap() {
      // The learned seed is clamped to the cap: even after a map grows to the cap, recordSlots
      // (which
      // normally reserves a class of headroom) cannot push the seed past maxSlots.
      LightStringMap.SizingHint hint =
          LightStringMap.buildSizingHint().initCapacity(2).maxCapacity(16).build();
      LightStringMap<Integer> map = LightStringMap.create(hint);
      for (int i = 0; i < 16; i++) {
        map.set("k" + i, i);
      }
      assertTrue(
          hint.currentSeedSlots() <= 16,
          "learned seed " + hint.currentSeedSlots() + " must not exceed the 16-slot cap");
    }
  }
}
