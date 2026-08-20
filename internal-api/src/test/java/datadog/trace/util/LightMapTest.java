package datadog.trace.util;

import static datadog.trace.util.LightMap.EmbeddingSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.LightMap.EntryReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LightMapTest {

  // ============ Instance API ============

  @Nested
  class InstanceTests {

    @Test
    void getOnEmptyMapReturnsNull() {
      LightMap<String, Integer> map = LightMap.createUncapped(LightMap.DEFAULT_CAPACITY);
      assertNull(map.get("absent"));
    }

    @Test
    void setThenGet() {
      LightMap<String, Integer> map = LightMap.createUncapped(8);
      map.set("a", 1);
      map.set("b", 2);
      assertEquals(1, map.get("a"));
      assertEquals(2, map.get("b"));
      assertNull(map.get("c"));
    }

    @Test
    void noArgCreateUncappedProducesUsableMap() {
      // The parameterless front door seeds the default capacity; exercise it end-to-end so the
      // convenience factory doesn't rot uncovered.
      LightMap<String, Integer> map = LightMap.createUncapped();
      map.set("a", 1);
      map.set("b", 2);
      assertEquals(1, map.get("a"));
      assertEquals(2, map.get("b"));
      assertEquals(2, map.size());
    }

    @Test
    void setOverwritesExistingKey() {
      LightMap<String, Integer> map = LightMap.createUncapped(8);
      map.set("a", 1);
      map.set("a", 42);
      assertEquals(42, map.get("a"));
    }

    @Test
    void removeMakesKeyAbsent() {
      LightMap<String, Integer> map = LightMap.createUncapped(8);
      map.set("a", 1);
      map.set("b", 2);
      map.remove("a");
      assertNull(map.get("a"));
      assertEquals(2, map.get("b"));
    }

    @Test
    void containsKeyDistinguishesPresenceFromAbsence() {
      LightMap<String, Integer> map = LightMap.createUncapped(8);
      assertFalse(map.containsKey("a"));
      map.set("a", 1);
      assertTrue(map.containsKey("a"));
      assertFalse(map.containsKey("b"));
      map.remove("a");
      assertFalse(map.containsKey("a"));
    }

    @Test
    void setRejectsNullValue() {
      LightMap<String, Integer> map = LightMap.createUncapped(8);
      assertThrows(NullPointerException.class, () -> map.set("a", null));
      assertFalse(map.containsKey("a"));
    }

    @Test
    void sizeTracksLiveEntries() {
      LightMap<String, Integer> map = LightMap.createUncapped(8);
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
      LightMap<String, Integer> map = LightMap.createUncapped(8);
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

      LightMap<String, Integer> spread = LightMap.createUncapped(8);
      for (int i = 0; i < count; i++) {
        spread.set("spread." + i, i);
      }

      LightMap<String, Integer> clustered = LightMap.createUncapped(8);
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
    void collidingHashCodesDoNotExplodeMemory() {
      // Regression: keys sharing an identical hashCode() land on the same home slot in EVERY table
      // size, so growing never shortens their probe chain. A pure probe-bound grow trigger would
      // double the table on every insert past MAX_PROBES, ballooning an uncapped map to hundreds of
      // millions of slots from a few dozen keys (an adversarial-input OOM). The
      // MAX_SLOTS_PER_LIVE_ENTRY backstop must keep the table O(live entries) while every key stays
      // retrievable.
      List<String> keys = identicalHashCodeKeys(32);
      int sharedHash = keys.get(0).hashCode();
      for (String key : keys) {
        assertEquals(sharedHash, key.hashCode(), "fixture keys must share one hashCode: " + key);
      }

      LightMap<String, Integer> map = LightMap.createUncapped(8);
      for (int i = 0; i < keys.size(); i++) {
        map.set(keys.get(i), i);
      }

      int slots = EmbeddingSupport.numSlots(map.dataForTesting());
      assertTrue(
          slots <= keys.size() * 16,
          "colliding keys must stay bounded, not explode: " + slots + " slots for " + keys.size());
      assertEquals(keys.size(), map.size());
      for (int i = 0; i < keys.size(); i++) {
        assertEquals(i, map.get(keys.get(i)), keys.get(i));
      }
    }

    @Test
    void insertReclaimsEarlierTombstoneInsteadOfExtendingChain() {
      // A chain of same-home-slot keys, with one removed mid-chain, leaves a tombstone. A later
      // insert of a new key on that chain must reclaim the (earlier, shorter-displacement)
      // tombstone
      // rather than walk past it to a fresh null -- keeping the chain short and avoiding a needless
      // grow.
      List<String> colliding = collidingKeys(5);
      LightMap<String, Integer> map = LightMap.createUncapped(16);
      for (int i = 0; i < 4; i++) {
        map.set(colliding.get(i), i);
      }
      Object[] data = map.dataForTesting();
      int numSlots = EmbeddingSupport.numSlots(data);
      int reclaimedSlot = slotOf(data, numSlots, colliding.get(1));
      assertTrue(reclaimedSlot >= 0, "second key should be present before removal");

      map.remove(colliding.get(1)); // tombstone mid-chain
      map.set(colliding.get(4), 4); // new key on the same chain

      Object[] after = map.dataForTesting();
      assertEquals(16, EmbeddingSupport.numSlots(after), "reusing the tombstone avoids a grow");
      assertEquals(
          colliding.get(4),
          after[reclaimedSlot],
          "the new key should reuse the earlier tombstone slot, not extend the chain");
      assertEquals(4, map.get(colliding.get(4)));
      assertNull(map.get(colliding.get(1)));
    }

    @Test
    void growsAndPreservesAllEntries() {
      // initial capacity 2 forces several resizes as we insert well past it.
      LightMap<String, Integer> map = LightMap.createUncapped(2);
      int n = 100;
      for (int i = 0; i < n; i++) {
        map.set("key-" + i, i);
      }
      for (int i = 0; i < n; i++) {
        assertEquals(i, map.get("key-" + i), "key-" + i);
      }
    }

    @Test
    void growsAndPreservesAllEntriesWithNonStringKeys() {
      // A non-String key type must survive resizing: expandMapData copies keys back generically,
      // so it must not assume String. initial capacity 2 forces several resizes.
      LightMap<Integer, String> map = LightMap.createUncapped(2);
      int n = 100;
      for (int i = 0; i < n; i++) {
        map.set(i, "value-" + i);
      }
      for (int i = 0; i < n; i++) {
        assertEquals("value-" + i, map.get(i), "key " + i);
      }
    }

    @Test
    void forEachVisitsEveryLiveEntry() {
      LightMap<String, Integer> map = LightMap.createUncapped(4);
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
      LightMap<String, Integer> map = LightMap.createUncapped(8);
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

    // Distinct strings that all share ONE hashCode() (not merely one home slot): the equal-hashCode
    // blocks "Aa" and "BB" both hash to 2112, and any concatenation of them yields the same
    // hashCode -- so these collide in every table size and can never be spread apart by growing.
    private List<String> identicalHashCodeKeys(int count) {
      String[] blocks = {"Aa", "BB"};
      List<String> keys = new ArrayList<>(count);
      for (int i = 0; keys.size() < count; i++) {
        StringBuilder sb = new StringBuilder();
        int bits = i;
        for (int b = 0; b < 5; b++) { // 2^5 = 32 distinct combinations
          sb.append(blocks[bits & 1]);
          bits >>>= 1;
        }
        keys.add(sb.toString());
      }
      return keys;
    }

    private int slotOf(Object[] data, int numSlots, String key) {
      for (int slot = 0; slot < numSlots; slot++) {
        if (key.equals(data[slot])) return slot;
      }
      return -1;
    }

    @Test
    void removeThenReinsertSameKey() {
      LightMap<String, Integer> map = LightMap.createUncapped(4);
      for (int i = 0; i < 4; i++) {
        map.set("k" + i, i);
      }
      map.remove("k1");
      assertNull(map.get("k1"));
      map.set("k1", 99);
      assertEquals(99, map.get("k1"));
    }

    @Test
    void forEachLoopVisitsAllLiveEntries() {
      LightMap<String, Integer> map = LightMap.createUncapped(8);
      map.set("a", 1);
      map.set("b", 2);
      map.set("c", 3);

      Map<String, Integer> seen = new HashMap<>();
      for (EntryReader<String, Integer> e : map) {
        seen.put(e.key(), e.value());
      }
      assertEquals(3, seen.size());
      assertEquals(1, seen.get("a"));
      assertEquals(2, seen.get("b"));
      assertEquals(3, seen.get("c"));
    }

    @Test
    void iterationSkipsRemovedAndEmptySlots() {
      LightMap<String, Integer> map = LightMap.createUncapped(8);
      for (int i = 0; i < 5; i++) {
        map.set("k" + i, i);
      }
      map.remove("k2"); // leaves a tombstone the iterator must skip

      Map<String, Integer> seen = new HashMap<>();
      for (EntryReader<String, Integer> e : map) {
        seen.put(e.key(), e.value());
      }
      assertEquals(4, seen.size());
      assertNull(seen.get("k2"));
      assertEquals(0, seen.get("k0"));
      assertEquals(4, seen.get("k4"));
    }

    @Test
    void iterationOverEmptyMapYieldsNothing() {
      LightMap<String, Integer> map = LightMap.createUncapped(8);
      Iterator<EntryReader<String, Integer>> it = map.iterator();
      assertFalse(it.hasNext());
      assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    void iteratorReusesASingleFlyweight() {
      // The reader handed back is the iterator itself, repositioned in place -- the same object
      // each
      // step. This pins the deliberate zero-allocation contract (and the "do not retain" caveat).
      LightMap<String, Integer> map = LightMap.createUncapped(8);
      map.set("a", 1);
      map.set("b", 2);

      Iterator<EntryReader<String, Integer>> it = map.iterator();
      EntryReader<String, Integer> first = it.next();
      EntryReader<String, Integer> second = it.next();
      assertSame(first, second);
      assertSame(it, first);
      assertFalse(it.hasNext());
    }

    @Test
    void iteratorRemoveIsUnsupported() {
      LightMap<String, Integer> map = LightMap.createUncapped(8);
      map.set("a", 1);
      Iterator<EntryReader<String, Integer>> it = map.iterator();
      it.next();
      assertThrows(UnsupportedOperationException.class, it::remove);
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
      assertEquals(EmbeddingSupport.SLOT_NOT_FOUND, EmbeddingSupport.findSlot(null, "x"));
    }

    @Test
    void removedTombstoneRendersDistinctlyForHeapInspection() {
      // The deletion sentinel is only ever observed by a human reading a heap dump / debugger,
      // never by production code, so its toString() has no code-path caller. Pin the rendering
      // here so the debug aid can't silently regress.
      assertEquals("--REMOVED--", EmbeddingSupport.REMOVED.toString());
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
      assertEquals(LightMap.DEFAULT_CAPACITY, EmbeddingSupport.numSlots(data));
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

    // The following three tests drive the wraparound (second) probe loop in the spine, the path
    // where a probe runs off the end of the table and resumes at slot 0. Integer keys make the home
    // slot deterministic: for a value v < 2^16, key.hashCode() == v and its high half is zero, so
    // preferredSlot(8, v) == (v & 7). Keys 7, 15, 23 all home to slot 7 (the last slot), so a probe
    // starting there must wrap. Distances stay under MAX_PROBES, so an 8-slot table never resizes.
    @Test
    void findSlotWrapsPastEndToLocateKey() {
      Object[] data = null;
      data = EmbeddingSupport.set(8, data, 7, "A"); // home slot 7
      data = EmbeddingSupport.set(8, data, 15, "B"); // home slot 7 taken -> wraps to slot 0
      assertEquals(8, EmbeddingSupport.numSlots(data)); // no resize

      // 15's home (7) is occupied by 7, so the lookup must run off the end and resume the scan at
      // slot 0 -- the wraparound loop -- to find it. get()/remove() both route through findSlot.
      assertEquals(0, EmbeddingSupport.findSlot(data, 15));
      assertTrue(EmbeddingSupport.containsKey(data, 15));
      assertEquals("B", EmbeddingSupport.get(data, 15));
    }

    @Test
    void missTerminatesInWraparoundLoopAtNull() {
      Object[] data = null;
      data = EmbeddingSupport.set(8, data, 7, "A"); // home 7 -> slot 7
      data = EmbeddingSupport.set(8, data, 15, "B"); // home 7 -> wraps to slot 0

      // 23 also homes to slot 7; the probe wraps (slot 7 occupied, no match) and terminates at the
      // first null in the second loop -- an absent-key miss reached only via wraparound.
      assertEquals(EmbeddingSupport.SLOT_NOT_FOUND, EmbeddingSupport.findSlot(data, 23));
      assertFalse(EmbeddingSupport.containsKey(data, 23));
      assertNull(EmbeddingSupport.get(data, 23));
    }

    @Test
    void insertReusesTombstoneFoundAfterWraparound() {
      Object[] data = null;
      data = EmbeddingSupport.set(8, data, 7, "A"); // home 7 -> slot 7
      data = EmbeddingSupport.set(8, data, 15, "B"); // home 7 -> wraps to slot 0
      assertTrue(EmbeddingSupport.remove(data, 15)); // slot 0 becomes a tombstone

      // 23 homes to slot 7 (occupied), wraps, and meets the tombstone at slot 0 before any null.
      // findInsertionSlot must reclaim that tombstone in the wraparound loop rather than walk on.
      data = EmbeddingSupport.set(8, data, 23, "C");
      assertEquals(8, EmbeddingSupport.numSlots(data)); // reclaimed in place, no resize
      assertEquals(0, EmbeddingSupport.findSlot(data, 23)); // reused the very slot 15 vacated
      assertEquals("C", EmbeddingSupport.get(data, 23));
      assertEquals(2, EmbeddingSupport.size(data));
    }

    @Test
    void spineIteratorVisitsAllLiveEntriesAndSkipsTombstones() {
      Object[] data = null;
      for (int i = 0; i < 5; i++) {
        data = EmbeddingSupport.set(8, data, "k" + i, i);
      }
      EmbeddingSupport.remove(data, "k3"); // tombstone to skip

      Map<String, Integer> seen = new HashMap<>();
      Iterator<EntryReader<String, Integer>> it = EmbeddingSupport.iterator(data);
      while (it.hasNext()) {
        EntryReader<String, Integer> e = it.next();
        seen.put(e.key(), e.value());
      }
      assertEquals(4, seen.size());
      assertNull(seen.get("k3"));
      assertEquals(0, seen.get("k0"));
    }

    @Test
    void spineIterableDrivesForEachLoopAndIsReIterable() {
      Object[] data = null;
      data = EmbeddingSupport.set(8, data, "a", 1);
      data = EmbeddingSupport.set(8, data, "b", 2);

      Iterable<EntryReader<String, Integer>> view = EmbeddingSupport.iterable(data);
      // A fresh flyweight per iterator() call, so the view survives a second pass.
      for (int pass = 0; pass < 2; pass++) {
        Map<String, Integer> seen = new HashMap<>();
        for (EntryReader<String, Integer> e : view) {
          seen.put(e.key(), e.value());
        }
        assertEquals(2, seen.size(), "pass " + pass);
        assertEquals(1, seen.get("a"));
        assertEquals(2, seen.get("b"));
      }
    }

    @Test
    void spineIteratorOverNullDataYieldsNothing() {
      Iterator<EntryReader<String, Integer>> it = EmbeddingSupport.iterator(null);
      assertFalse(it.hasNext());
      assertThrows(NoSuchElementException.class, it::next);
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
    void expandToUndersizedCapacityRejectsRatherThanDroppingEntries() {
      Object[] data = null;
      for (int i = 0; i < 4; i++) {
        data = EmbeddingSupport.set(4, data, "k" + i, i);
      }
      Object[] fourEntries = data;
      assertThrows(
          IllegalArgumentException.class, () -> EmbeddingSupport.expandMapData(fourEntries, 1));
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
    void findSlotReportsSameMissSentinelForNullMapAndAbsentKey() {
      // findSlot follows the String.indexOf idiom: every miss returns SLOT_NOT_FOUND, whether the
      // map is null or simply lacks the key. The two cases must not diverge (they used to).
      Object[] data = EmbeddingSupport.set(8, null, "a", "A");
      assertEquals(EmbeddingSupport.SLOT_NOT_FOUND, EmbeddingSupport.findSlot(data, "absent"));
      assertEquals(EmbeddingSupport.SLOT_NOT_FOUND, EmbeddingSupport.findSlot(null, "absent"));
      assertEquals(-1, EmbeddingSupport.SLOT_NOT_FOUND);
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
      Object[] data =
          EmbeddingSupport.insertAt(4, null, EmbeddingSupport.SLOT_CAPACITY_REACHED, "a", "A");
      assertEquals("A", EmbeddingSupport.get(data, "a"));
    }

    @Test
    void insertAtRejectsNullKey() {
      assertThrows(
          NullPointerException.class,
          () ->
              EmbeddingSupport.insertAt(
                  4, null, EmbeddingSupport.SLOT_CAPACITY_REACHED, null, "A"));
    }

    @Test
    void insertAtRejectsNullValue() {
      assertThrows(
          NullPointerException.class,
          () ->
              EmbeddingSupport.insertAt(
                  4, null, EmbeddingSupport.SLOT_CAPACITY_REACHED, "a", null));
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
      Map<Object, Object> ctx = new HashMap<>();
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

    @Test
    void roundUpToPow2RejectsNegativeCapacity() {
      assertThrows(IllegalArgumentException.class, () -> EmbeddingSupport.roundUpToPow2(-1));
    }

    @Test
    void roundUpToPow2RejectsCapacityAboveMaxSlots() {
      // Integer.MAX_VALUE used to shift to Integer.MIN_VALUE here, silently turning an
      // "effectively unbounded" request into a permanent one-slot table.
      assertThrows(
          IllegalArgumentException.class, () -> EmbeddingSupport.roundUpToPow2(Integer.MAX_VALUE));
      assertThrows(
          IllegalArgumentException.class,
          () -> EmbeddingSupport.roundUpToPow2(EmbeddingSupport.MAX_SLOTS + 1));
      assertEquals(
          EmbeddingSupport.MAX_SLOTS, EmbeddingSupport.roundUpToPow2(EmbeddingSupport.MAX_SLOTS));
    }

    @Test
    void expandMapDataRejectsRequestedCapacityAboveMaxSlots() {
      // Growing a table already at MAX_SLOTS would need to double past it, overflowing the
      // backing array's length; expandMapData must reject that rather than allocate garbage.
      Object[] data = EmbeddingSupport.set(null, "a", "A");
      assertThrows(
          IllegalArgumentException.class,
          () -> EmbeddingSupport.expandMapData(data, EmbeddingSupport.MAX_SLOTS + 1));
    }
  }

  @Nested
  class AdaptiveSizingHintTests {

    @Test
    void freshHintSeedsAtDefault() {
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      assertEquals(LightMap.DEFAULT_HINT_SLOTS, hint.currentSeedSlots());
    }

    @Test
    void hintSeedsAFreshMapAtItsLearnedCapacity() {
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      LightMap<String, Integer> map = LightMap.create(hint);
      // A hint-seeded map allocates its backing array lazily, sized to the hint.
      map.set("a", 1);
      Object[] data = map.dataForTesting();
      assertEquals(LightMap.DEFAULT_HINT_SLOTS, EmbeddingSupport.numSlots(data));
    }

    @Test
    void growthRaisesSeedWithOneClassOfHeadroom() {
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      // Fill a hint-seeded map past its seed so it grows; the hint should learn the new size
      // PLUS one power-of-two class of headroom (so the steady-state load factor stays <= 0.5).
      LightMap<String, Integer> map = LightMap.create(hint);
      for (int i = 0; i < LightMap.DEFAULT_HINT_SLOTS + 1; i++) {
        map.set("k" + i, i);
      }
      int grownSlots = EmbeddingSupport.numSlots(map.dataForTesting());
      assertEquals(grownSlots * 2, hint.currentSeedSlots());
    }

    @Test
    void seedIsMonotonicMaxAcrossMaps() {
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      // A big map ratchets the hint up.
      LightMap<String, Integer> big = LightMap.create(hint);
      for (int i = 0; i < 20; i++) {
        big.set("k" + i, i);
      }
      int learned = hint.currentSeedSlots();
      assertTrue(learned > LightMap.DEFAULT_HINT_SLOTS);
      // A subsequent tiny map does not lower the learned seed.
      LightMap<String, Integer> small = LightMap.create(hint);
      small.set("a", 1);
      assertEquals(learned, hint.currentSeedSlots());
    }

    @Test
    void decayStepsSeedDownAfterInterval() {
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      // Ratchet the hint above the default so a step-down is observable.
      LightMap<String, Integer> big = LightMap.create(hint);
      for (int i = 0; i < 20; i++) {
        big.set("k" + i, i);
      }
      int learned = hint.currentSeedSlots();
      // One full decay interval of constructions steps the seed down exactly one class.
      for (int i = 0; i < LightMap.DECAY_INTERVAL; i++) {
        LightMap.<String, Integer>create(hint);
      }
      assertEquals(learned / 2, hint.currentSeedSlots());
    }

    @Test
    void decayFloorsAtMinimum() {
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      // Enough decay intervals to drive an un-ratcheted hint to the floor and hold there.
      int intervals = 32;
      for (int i = 0; i < intervals * LightMap.DECAY_INTERVAL; i++) {
        LightMap.<String, Integer>create(hint);
      }
      assertEquals(LightMap.MIN_HINT_SLOTS, hint.currentSeedSlots());
    }

    @Test
    void seedIsCappedAtMax() {
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      // A very large map cannot push the learned seed past the pre-provisioning ceiling.
      LightMap<String, Integer> big = LightMap.create(hint);
      for (int i = 0; i < LightMap.MAX_HINT_SLOTS * 4; i++) {
        big.set("k" + i, i);
      }
      assertTrue(hint.currentSeedSlots() <= LightMap.MAX_HINT_SLOTS);
      assertEquals(LightMap.MAX_HINT_SLOTS, hint.currentSeedSlots());
    }

    @Test
    void oneDecayStepStaysSafeThenSecondDecayRepins() {
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      // Learn a large size. With one class of headroom, `learned` is 2x the array the workload
      // physically grew into.
      LightMap<String, Integer> big = LightMap.create(hint);
      for (int i = 0; i < 20; i++) {
        big.set("k" + i, i);
      }
      int learned = hint.currentSeedSlots();

      // First decay lands the seed exactly on the physical high-water: the same workload now fits
      // without regrowing, so the seed holds (the headroom step-down is "free").
      for (int i = 0; i < LightMap.DECAY_INTERVAL; i++) {
        LightMap.<String, Integer>create(hint);
      }
      assertEquals(learned / 2, hint.currentSeedSlots());
      LightMap<String, Integer> stillFits = LightMap.create(hint);
      for (int i = 0; i < 20; i++) {
        stillFits.set("k" + i, i);
      }
      assertEquals(learned / 2, hint.currentSeedSlots());

      // Second decay probes below the need: the workload now regrows and snaps the seed back up.
      for (int i = 0; i < LightMap.DECAY_INTERVAL; i++) {
        LightMap.<String, Integer>create(hint);
      }
      assertEquals(learned / 4, hint.currentSeedSlots());
      LightMap<String, Integer> recovered = LightMap.create(hint);
      for (int i = 0; i < 20; i++) {
        recovered.set("k" + i, i);
      }
      assertEquals(learned, hint.currentSeedSlots());
    }

    @Test
    void hintSeededMapStoresAndReadsBackCorrectly() {
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      LightMap<String, Integer> map = LightMap.create(hint);
      for (int i = 0; i < 50; i++) {
        map.set("k" + i, i);
      }
      for (int i = 0; i < 50; i++) {
        assertEquals(i, map.get("k" + i));
      }
    }
  }

  // ============ hint-aware spine set(AdaptiveSizingHint, ...) overload ============

  @Nested
  class SpineHintTests {

    @Test
    void spineSetSeedsAFreshTableFromTheHint() {
      // Dropping to the spine keeps the hint's sizing: a fresh table seeds from seedSlots(), just
      // like LightMap.create(hint) does through the object tier.
      LightMap.AdaptiveSizingHint hint =
          LightMap.AdaptiveSizingHint.builder().initCapacity(4).build();
      Object[] data = EmbeddingSupport.set(hint, null, "a", 1);
      assertEquals(4, EmbeddingSupport.numSlots(data));
      assertEquals(1, (Object) EmbeddingSupport.get(data, "a"));
    }

    @Test
    void spineSetTeachesTheHintOnGrow() {
      // A grow through the spine ratchets the hint up with one class of headroom -- identical to
      // the
      // object tier's growthRaisesSeedWithOneClassOfHeadroom.
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      Object[] data = null;
      for (int i = 0; i < LightMap.DEFAULT_HINT_SLOTS + 1; i++) {
        data = EmbeddingSupport.set(hint, data, "k" + i, i);
      }
      int grownSlots = EmbeddingSupport.numSlots(data);
      assertEquals(grownSlots * 2, hint.currentSeedSlots());
    }

    @Test
    void spineSetDoesNotEnforceTheHintCap() {
      // The cap guardrail does NOT follow to the spine: an Object[] return cannot signal rejection,
      // so a capped hint used at the spine grows past its cap and always stores. (Contrast the
      // object tier, which rejects at the cap -- see CapTests.)
      LightMap.AdaptiveSizingHint hint =
          LightMap.AdaptiveSizingHint.builder().initCapacity(2).maxCapacity(4).build();
      Object[] data = null;
      for (int i = 0; i < 12; i++) {
        data = EmbeddingSupport.set(hint, data, "k" + i, i);
      }
      assertTrue(EmbeddingSupport.numSlots(data) > 4, "spine grew past the hint's 4-slot cap");
      for (int i = 0; i < 12; i++) {
        assertEquals(
            i, (Object) EmbeddingSupport.get(data, "k" + i), "every key stored despite the cap");
      }
    }

    @Test
    void spineSetOverwriteInPlaceDoesNotTeachTheHint() {
      // An in-place overwrite neither grows nor teaches the hint -- same array back, seed
      // unchanged.
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      Object[] data = EmbeddingSupport.set(hint, null, "a", 1);
      int seedAfterFirstInsert = hint.currentSeedSlots();

      Object[] afterOverwrite = EmbeddingSupport.set(hint, data, "a", 2);
      assertSame(data, afterOverwrite, "overwrite reuses the same backing array");
      assertEquals(2, (Object) EmbeddingSupport.get(afterOverwrite, "a"));
      assertEquals(seedAfterFirstInsert, hint.currentSeedSlots(), "no grow -> hint not taught");
    }
  }

  // ============ maxCapacity hard cap + boolean set() ============

  @Nested
  class CapTests {

    @Test
    void uncappedMapAlwaysReturnsTrueFromSet() {
      // The plain capacity constructor is uncapped: set stores unconditionally and never rejects,
      // even well past the initial capacity.
      LightMap<String, Integer> map = LightMap.createUncapped(2);
      for (int i = 0; i < 100; i++) {
        assertTrue(map.set("k" + i, i), "uncapped set should always store");
      }
      assertEquals(100, map.size());
    }

    @Test
    void hintWithoutMaxCapacityIsUncapped() {
      // createUncappedAdaptiveSizingHint() carries no cap, so set always stores.
      LightMap.AdaptiveSizingHint hint = LightMap.createUncappedAdaptiveSizingHint();
      LightMap<String, Integer> map = LightMap.create(hint);
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
      LightMap.AdaptiveSizingHint hint = LightMap.createCappedAdaptiveSizingHint(8);
      LightMap<String, Integer> map = LightMap.create(hint);
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
      LightMap.AdaptiveSizingHint hint = LightMap.createCappedAdaptiveSizingHint(8);
      LightMap<String, Integer> map = LightMap.create(hint);
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
      LightMap.AdaptiveSizingHint hint =
          LightMap.AdaptiveSizingHint.builder().initCapacity(2).maxCapacity(16).build();
      LightMap<String, Integer> map = LightMap.create(hint);
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
      LightMap.AdaptiveSizingHint hint = LightMap.createCappedAdaptiveSizingHint(8);
      LightMap<String, Integer> map = LightMap.create(hint);
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
      LightMap.AdaptiveSizingHint hint = LightMap.createCappedAdaptiveSizingHint(5);
      LightMap<String, Integer> map = LightMap.create(hint);
      for (int i = 0; i < 8; i++) {
        assertTrue(map.set("k" + i, i));
      }
      assertFalse(map.set("k8", 8), "cap of 5 rounds up to 8 slots");
    }

    @Test
    void buildThrowsWhenInitCapacityExceedsMaxCapacity() {
      assertThrows(
          IllegalArgumentException.class,
          () -> LightMap.AdaptiveSizingHint.builder().initCapacity(16).maxCapacity(8).build());
    }

    @Test
    void cappedHintNeverSeedsAMapLargerThanTheCap() {
      // The learned seed is clamped to the cap: even after a map grows to the cap, recordSlots
      // (which
      // normally reserves a class of headroom) cannot push the seed past maxSlots.
      LightMap.AdaptiveSizingHint hint =
          LightMap.AdaptiveSizingHint.builder().initCapacity(2).maxCapacity(16).build();
      LightMap<String, Integer> map = LightMap.create(hint);
      for (int i = 0; i < 16; i++) {
        map.set("k" + i, i);
      }
      assertTrue(
          hint.currentSeedSlots() <= 16,
          "learned seed " + hint.currentSeedSlots() + " must not exceed the 16-slot cap");
    }

    @Test
    void createCappedRejectsCapacityThatOverflowsOnRounding() {
      // roundUpToPow2(Integer.MAX_VALUE) used to silently overflow to Integer.MIN_VALUE, so the
      // requested "effectively unbounded" cap instead became a permanent one-slot map with no
      // exception. It must now fail loudly at construction instead.
      assertThrows(IllegalArgumentException.class, () -> LightMap.createCapped(Integer.MAX_VALUE));
    }

    @Test
    void createCappedRejectsNegativeCapacity() {
      assertThrows(IllegalArgumentException.class, () -> LightMap.createCapped(-1));
    }

    @Test
    void createUncappedRejectsCapacityAboveMaxSlotsAtConstruction() {
      assertThrows(IllegalArgumentException.class, () -> LightMap.createUncapped((1 << 29) + 1));
    }

    @Test
    void createCappedTwoArgRejectsCapacityAboveMaxSlots() {
      assertThrows(IllegalArgumentException.class, () -> LightMap.createCapped(8, (1 << 29) + 1));
    }

    @Test
    void adaptiveSizingHintBuilderRejectsCapacityAboveMaxSlots() {
      assertThrows(
          IllegalArgumentException.class,
          () -> LightMap.AdaptiveSizingHint.builder().maxCapacity((1 << 29) + 1).build());
    }
  }

  // ============ createCapped(...) untuned hard-capped front door ============

  @Nested
  class CreateCappedTests {

    @Test
    void createCappedRejectsNewKeyOncePhysicallyFullAtCap() {
      // createCapped(8) hard-bounds the table at 8 slots with no hint: eight distinct keys fill it,
      // a ninth genuinely new key is rejected and leaves the map unchanged.
      LightMap<String, Integer> map = LightMap.createCapped(8);
      for (int i = 0; i < 8; i++) {
        assertTrue(map.set("k" + i, i), "slot " + i + " should store");
      }
      assertEquals(8, EmbeddingSupport.numSlots(map.dataForTesting()), "capped at 8 slots");
      assertFalse(map.set("overflow", 99), "new key past the cap should be rejected");
      assertEquals(8, map.size(), "rejected set must not change the map");
      for (int i = 0; i < 8; i++) {
        assertEquals(i, map.get("k" + i));
      }
    }

    @Test
    void createCappedRoundsCapUpToPowerOfTwo() {
      // A non-power-of-two cap rounds up: createCapped(5) becomes an 8-slot cap.
      LightMap<String, Integer> map = LightMap.createCapped(5);
      for (int i = 0; i < 8; i++) {
        assertTrue(map.set("k" + i, i));
      }
      assertFalse(map.set("k8", 8), "cap of 5 rounds up to 8 slots");
    }

    @Test
    void createCappedSeedClampedToCapSmallerThanDefault() {
      // With a cap below the default seed, the seed is clamped down to the cap: createCapped(4)
      // seeds and caps at 4 slots, so the fifth new key is rejected.
      LightMap<String, Integer> map = LightMap.createCapped(4);
      for (int i = 0; i < 4; i++) {
        assertTrue(map.set("k" + i, i));
      }
      assertEquals(4, EmbeddingSupport.numSlots(map.dataForTesting()), "capped at 4 slots");
      assertFalse(map.set("k4", 4), "the fifth key exceeds the 4-slot cap");
    }

    @Test
    void createCappedTwoArgSeedsSmallAndGrowsToTheCap() {
      // createCapped(2, 16) seeds at 2 slots and grows through the power-of-two classes up to the
      // 16-slot cap, retaining every entry; the 17th new key is rejected.
      LightMap<String, Integer> map = LightMap.createCapped(2, 16);
      for (int i = 0; i < 16; i++) {
        assertTrue(map.set("k" + i, i), "slot " + i + " should store below the cap");
      }
      assertEquals(16, EmbeddingSupport.numSlots(map.dataForTesting()));
      assertFalse(map.set("k16", 16), "the 17th key exceeds the 16-slot cap");
      for (int i = 0; i < 16; i++) {
        assertEquals(i, map.get("k" + i));
      }
    }

    @Test
    void createCappedTwoArgRoundsBothToPowerOfTwo() {
      // Both arguments round up independently: createCapped(3, 5) seeds at 4 slots, caps at 8.
      LightMap<String, Integer> map = LightMap.createCapped(3, 5);
      for (int i = 0; i < 8; i++) {
        assertTrue(map.set("k" + i, i));
      }
      assertFalse(map.set("k8", 8), "cap of 5 rounds up to 8 slots");
    }

    @Test
    void createCappedTwoArgThrowsWhenSeedExceedsCap() {
      assertThrows(IllegalArgumentException.class, () -> LightMap.createCapped(16, 8));
    }
  }
}
