package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Read-through support, slice 1 (read path): a child {@link TagMap} with a frozen parent reads
 * through to the parent on a local miss, while local entries shadow the parent (local-wins).
 * Removal/tombstones and bulk (iteration/serialize) union come in later slices.
 */
class TagMapReadThroughTest {

  private static TagMap frozenParent(TagMapType type) {
    TagMap parent = type.create();
    parent.set("a", "parent-a");
    parent.set("b", "parent-b");
    parent.freeze();
    return parent;
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void readsThroughToParentOnMiss(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("c", "child-c");

    assertEquals("parent-a", child.getString("a")); // miss locally -> read through
    assertEquals("parent-b", child.getString("b"));
    assertEquals("child-c", child.getString("c")); // local
    assertNull(child.getString("missing"));
    assertTrue(child.containsKey("a"));
    assertFalse(child.containsKey("missing"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void localEntryShadowsParent(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("b", "child-b"); // same key as parent

    assertEquals("child-b", child.getString("b")); // local wins
    assertEquals("parent-a", child.getString("a")); // parent still visible
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void estimateSizeIsUpperBound(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("b", "child-b"); // shadows parent "b"
    child.set("c", "child-c");

    // true union = {a, b, c} = 3; estimate over-counts the shadowed "b": local 2 + parent 2 = 4
    assertEquals(4, child.estimateSize());
    assertTrue(child.estimateSize() >= 3, "estimateSize must be an upper bound on the true size");
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void emptinessSemantics(TagMapType type) {
    TagMap emptyOverEmpty = type.createFromParent(type.create().freeze());
    assertTrue(emptyOverEmpty.isEmpty());
    assertTrue(emptyOverEmpty.isDefinitelyEmpty());

    TagMap emptyOverNonEmpty = type.createFromParent(frozenParent(type));
    assertFalse(emptyOverNonEmpty.isEmpty(), "a non-empty parent makes the map non-empty");
    assertFalse(emptyOverNonEmpty.isDefinitelyEmpty());

    assertTrue((type.create()).isDefinitelyEmpty());
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void parentMustBeFrozen(TagMapType type) {
    TagMap mutableParent = type.create();
    assertThrows(IllegalStateException.class, () -> type.createFromParent(mutableParent));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void emptyParentIsDroppedNotAttached(TagMapType type) {
    TagMap emptyFrozen = type.create();
    emptyFrozen.freeze();

    TagMap overEmpty = type.createFromParent(emptyFrozen);
    // an empty frozen parent contributes nothing and never will -> dropped, no read-through cost
    assertNull(overEmpty.parent, "empty parent should be dropped");
    assertTrue(overEmpty.isDefinitelyEmpty());
    overEmpty.set("x", "x-val"); // still a normal mutable map
    assertEquals("x-val", overEmpty.getString("x"));

    // a non-empty parent is still attached
    TagMap overNonEmpty = type.createFromParent(frozenParent(type));
    assertNotNull(overNonEmpty.parent, "non-empty parent must be attached");
    assertEquals("parent-a", overNonEmpty.getString("a"));
  }

  // --- slice 2: removal / tombstones ---

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void removingParentKeyHidesItFromChildButNotFromParent(TagMapType type) {
    TagMap parent = frozenParent(type);
    TagMap child = type.createFromParent(parent);

    assertEquals("parent-a", child.getString("a")); // visible before removal
    child.remove("a");

    assertNull(child.getString("a")); // tombstoned: no longer reads through
    assertFalse(child.containsKey("a"));
    assertEquals("parent-b", child.getString("b")); // other parent keys unaffected
    assertEquals("parent-a", parent.getString("a")); // frozen parent untouched
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void removeReturnsPriorVisibleValueViaParent(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));

    // Map.remove contract: the key was present (via read-through), so removal reports it.
    assertTrue(child.remove("a"), "removing a parent-exposed key should report it was present");
    assertNull(child.getString("a"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void reSettingARemovedKeyRestoresVisibility(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));

    child.remove("a");
    assertNull(child.getString("a"));

    child.set("a", "child-a"); // re-set clears the tombstone
    assertEquals("child-a", child.getString("a"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void removingAKeyThatIsBothLocalAndParentHidesBoth(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("b", "child-b"); // shadows parent "b"

    assertEquals("child-b", child.getString("b"));
    child.remove("b");

    assertNull(child.getString("b"), "removal must hide both the local entry and the parent's");
    assertEquals("parent-b", frozenParent(type).getString("b")); // parent still has it
  }

  // --- slice 3a: bulk forEach union + exact size/isEmpty ---

  private static Map<String, Object> collect(TagMap map) {
    Map<String, Object> out = new HashMap<>();
    map.forEach(e -> out.put(e.tag(), e.objectValue()));
    return out;
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void forEachEmitsDedupedUnionLocalWins(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type)); // parent {a, b}
    child.set("b", "child-b"); // shadows parent "b"
    child.set("c", "child-c");

    Map<String, Object> u = collect(child);
    assertEquals(3, u.size(), "union {a, b, c} with b deduped");
    assertEquals("parent-a", u.get("a")); // read-through
    assertEquals("child-b", u.get("b")); // local wins (no duplicate emit)
    assertEquals("child-c", u.get("c"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void forEachSkipsTombstonedParentKeys(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("c", "child-c");
    child.remove("a"); // tombstone parent's "a"

    Map<String, Object> u = collect(child);
    assertEquals(2, u.size());
    assertFalse(u.containsKey("a"));
    assertEquals("parent-b", u.get("b"));
    assertEquals("child-c", u.get("c"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void biConsumerForEachAlsoEmitsUnion(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("c", "child-c");

    Map<String, Object> out = new HashMap<>();
    child.forEach(out, (m, e) -> m.put(e.tag(), e.objectValue())); // non-capturing: alloc-free path
    assertEquals(3, out.size());
    assertEquals("parent-a", out.get("a"));
    assertEquals("child-c", out.get("c"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void sizeIsExactUnion(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("b", "child-b"); // shadows
    child.set("c", "child-c");
    assertEquals(3, child.size()); // {a, b, c} — b deduped, not 4

    child.remove("a");
    assertEquals(2, child.size()); // {b, c}
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void isEmptyExactWhenAllParentKeysTombstonedAndNoLocal(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type)); // parent {a, b}
    assertFalse(child.isEmpty());

    child.remove("a");
    child.remove("b");
    assertTrue(child.isEmpty(), "all parent keys tombstoned and no local entries -> empty");
    assertEquals(0, child.size());
  }

  // --- slice 3b: pull-based iterators / collection views ---

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void iteratorEmitsDedupedUnion(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("b", "child-b"); // shadows parent "b"
    child.set("c", "child-c");

    Map<String, Object> u = new HashMap<>();
    Iterator<TagMap.EntryReader> it = child.iterator();
    while (it.hasNext()) {
      TagMap.EntryReader e = it.next();
      u.put(e.tag(), e.objectValue());
    }
    assertEquals(3, u.size());
    assertEquals("parent-a", u.get("a"));
    assertEquals("child-b", u.get("b")); // local wins, emitted once
    assertEquals("child-c", u.get("c"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void keySetReflectsUnionAndTombstones(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("c", "child-c");

    Set<String> keys = child.keySet();
    assertEquals(3, keys.size()); // a, b, c
    assertTrue(keys.contains("a"));
    assertTrue(keys.contains("c"));

    child.remove("a");
    assertEquals(2, child.keySet().size());
    assertFalse(child.keySet().contains("a"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void valuesAndEntrySetReflectUnion(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("b", "child-b"); // shadows parent "b"

    assertEquals(2, child.entrySet().size()); // {a, b} — b deduped
    assertTrue(child.values().contains("child-b")); // local-won value
    assertTrue(child.values().contains("parent-a"));
    assertFalse(child.values().contains("parent-b"), "shadowed parent value must not appear");
  }

  // --- slice 3c: putAll from a read-through source copies the visible union, not just locals ---

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void putAllFromReadThroughSourceCopiesFullVisibleUnion(TagMapType type) {
    TagMap source = type.createFromParent(frozenParent(type)); // parent {a, b}
    source.set("b", "child-b"); // shadows parent b
    source.set("c", "child-c");

    TagMap dest = type.create(); // empty -> putAllIntoEmptyMap path
    dest.putAll(source);

    // the parent-visible "a" must land too, not just source's local entries
    assertEquals(3, dest.size());
    assertEquals("parent-a", dest.getString("a"));
    assertEquals("child-b", dest.getString("b")); // local-won value, deduped
    assertEquals("child-c", dest.getString("c"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void putAllMergeFromReadThroughSourceCopiesVisibleUnion(TagMapType type) {
    TagMap source = type.createFromParent(frozenParent(type)); // {a, b}

    TagMap dest = type.create();
    dest.set("z", "dest-z"); // dest non-empty -> putAllMerge path

    dest.putAll(source);
    assertEquals(3, dest.size()); // {a, b, z}
    assertEquals("parent-a", dest.getString("a"));
    assertEquals("parent-b", dest.getString("b"));
    assertEquals("dest-z", dest.getString("z"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void putAllFromReadThroughSourceHonorsTombstones(TagMapType type) {
    TagMap source = type.createFromParent(frozenParent(type));
    source.remove("a"); // tombstone parent's "a"

    TagMap dest = type.create();
    dest.putAll(source);

    assertEquals(1, dest.size());
    assertFalse(dest.containsKey("a"), "tombstoned key must not be copied");
    assertEquals("parent-b", dest.getString("b"));
  }

  // --- slice 4: behavior-identical to a copy-down / flat map ---

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void copyIsObservationallyIdentical(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type)); // {a, b}
    child.set("b", "child-b"); // shadows parent "b"
    child.set("c", "child-c");

    TagMap copy = child.copy();
    assertEquals(child.size(), copy.size());
    assertEquals("parent-a", copy.getString("a")); // copy still reads through
    assertEquals("child-b", copy.getString("b"));
    assertEquals("child-c", copy.getString("c"));
    assertEquals(collect(child), collect(copy)); // same union
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void copyIsIndependentlyMutable(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("c", "child-c");

    TagMap copy = child.copy();
    copy.set("c", "copy-c"); // mutate copy's local
    copy.remove("a"); // tombstone on copy only

    assertEquals("child-c", child.getString("c"), "original unaffected by copy mutation");
    assertEquals("parent-a", child.getString("a"), "original still reads through a");
    assertEquals("copy-c", copy.getString("c"));
    assertNull(copy.getString("a"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void copyPreservesTombstones(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.remove("a"); // tombstone "a"

    TagMap copy = child.copy();
    assertNull(copy.getString("a"), "tombstone must carry into the copy");
    assertEquals("parent-b", copy.getString("b"));
  }

  /** The contract that lets the consumer flip mergedTracerTags to a parent. */
  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void readThroughMatchesAnEquivalentFlatMap(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("b", "child-b");
    child.set("c", "child-c");

    TagMap flat = type.create();
    flat.set("a", "parent-a");
    flat.set("b", "child-b");
    flat.set("c", "child-c");

    assertEquals(flat.size(), child.size());
    assertEquals(collect(flat), collect(child));
    assertEquals(flat.keySet(), child.keySet());
    for (String k : new String[] {"a", "b", "c", "missing"}) {
      assertEquals(flat.getString(k), child.getString(k), "mismatch for key " + k);
    }
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void immutableCopyOfReadThroughIsFrozenAndStillReadsThrough(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("c", "child-c");

    TagMap frozen = child.immutableCopy();
    assertTrue(frozen.isFrozen());
    assertEquals("parent-a", frozen.getString("a")); // union preserved
    assertEquals("child-c", frozen.getString("c"));
    assertThrows(IllegalStateException.class, () -> frozen.set("x", "y")); // frozen blocks writes
  }

  // --- slice 5: multi-level chains (baggage-style layering over more than one frozen parent) ---

  /**
   * Builds a 3-level chain leaf -&gt; mid -&gt; grandparent (both ancestors frozen) and returns the
   * leaf. Visible union, nearest-level-wins: {a=gp-a, b=mid-b, c=leaf-c, d=mid-d, e=leaf-e}.
   */
  private static TagMap threeLevelLeaf(TagMapType type) {
    TagMap grandparent = type.create();
    grandparent.set("a", "gp-a");
    grandparent.set("b", "gp-b");
    grandparent.set("c", "gp-c");
    grandparent.freeze();

    TagMap mid = type.createFromParent(grandparent);
    mid.set("b", "mid-b"); // shadows grandparent b
    mid.set("d", "mid-d");
    mid.freeze();

    TagMap leaf = type.createFromParent(mid);
    leaf.set("c", "leaf-c"); // shadows grandparent c (mid doesn't define c)
    leaf.set("e", "leaf-e");
    return leaf;
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void getWalksTheWholeChainNearestWins(TagMapType type) {
    TagMap leaf = threeLevelLeaf(type);
    assertEquals("gp-a", leaf.getString("a")); // only in grandparent (two levels up)
    assertEquals("mid-b", leaf.getString("b")); // mid shadows grandparent
    assertEquals("leaf-c", leaf.getString("c")); // leaf shadows grandparent
    assertEquals("mid-d", leaf.getString("d"));
    assertEquals("leaf-e", leaf.getString("e"));
    assertNull(leaf.getString("missing"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void sizeIsExactUnionAcrossChain(TagMapType type) {
    assertEquals(5, threeLevelLeaf(type).size()); // {a, b, c, d, e}, shadowed duplicates deduped
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void forEachEmitsDedupedUnionAcrossChain(TagMapType type) {
    Map<String, Object> u = collect(threeLevelLeaf(type));
    assertEquals(5, u.size());
    assertEquals("gp-a", u.get("a"));
    assertEquals("mid-b", u.get("b")); // nearest ancestor wins over grandparent
    assertEquals("leaf-c", u.get("c")); // leaf wins
    assertEquals("mid-d", u.get("d"));
    assertEquals("leaf-e", u.get("e"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void iteratorEmitsDedupedUnionAcrossChain(TagMapType type) {
    Map<String, Object> u = new HashMap<>();
    Iterator<TagMap.EntryReader> it = threeLevelLeaf(type).iterator();
    while (it.hasNext()) {
      TagMap.EntryReader e = it.next();
      u.put(e.tag(), e.objectValue());
    }
    assertEquals(5, u.size());
    assertEquals("gp-a", u.get("a"));
    assertEquals("mid-b", u.get("b"));
    assertEquals("leaf-c", u.get("c"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void keySetReflectsChainUnion(TagMapType type) {
    Set<String> keys = threeLevelLeaf(type).keySet();
    assertEquals(5, keys.size());
    for (String k : new String[] {"a", "b", "c", "d", "e"}) {
      assertTrue(keys.contains(k), "missing key " + k);
    }
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void leafTombstoneHidesGrandparentOnlyKey(TagMapType type) {
    TagMap leaf = threeLevelLeaf(type);
    leaf.remove("a"); // "a" lives only in the grandparent, two levels up
    assertNull(leaf.getString("a"));
    assertFalse(leaf.containsKey("a"));
    assertFalse(leaf.keySet().contains("a"));
    assertEquals(4, leaf.size()); // {b, c, d, e}
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void intermediateAncestorTombstoneIsHonoredByBulkViews(TagMapType type) {
    // mid removes an inherited grandparent key, THEN is frozen and reused as a parent. A non-leaf
    // level can therefore carry its own tombstones. Point lookups recurse through mid's tombstone
    // (correct); the bulk views must agree and not re-emit the key.
    TagMap grandparent = type.create();
    grandparent.set("a", "gp-a");
    grandparent.set("b", "gp-b");
    grandparent.freeze();

    TagMap mid = type.createFromParent(grandparent);
    mid.remove("a"); // tombstone an inherited key before freezing
    mid.freeze();

    TagMap leaf = type.createFromParent(mid);

    // point lookups (recurse -> already correct)
    assertNull(leaf.getString("a"));
    assertFalse(leaf.containsKey("a"));
    assertEquals("gp-b", leaf.getString("b"));

    // bulk views must agree: mid's tombstone hides grandparent's "a"
    assertEquals(1, leaf.size());
    assertFalse(leaf.keySet().contains("a"));

    Map<String, Object> viaForEach = collect(leaf);
    assertEquals(1, viaForEach.size());
    assertFalse(viaForEach.containsKey("a"));
    assertEquals("gp-b", viaForEach.get("b"));

    Map<String, Object> viaIterator = new HashMap<>();
    Iterator<TagMap.EntryReader> it = leaf.iterator();
    while (it.hasNext()) {
      TagMap.EntryReader e = it.next();
      viaIterator.put(e.tag(), e.objectValue());
    }
    assertEquals(1, viaIterator.size());
    assertFalse(viaIterator.containsKey("a"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void observationallyEmptyIntermediateParentIsDroppedNotAttached(TagMapType type) {
    // A frozen mid level with no local entries whose every inherited key is tombstoned is
    // observationally empty even though a local (grandparent) level still holds entries.
    // createFromParent must drop it (exact isEmpty), so the leaf stays consistent: isEmpty() must
    // agree with size()/lookup/iteration.
    TagMap grandparent = type.create();
    grandparent.set("a", "gp-a");
    grandparent.freeze();

    TagMap mid = type.createFromParent(grandparent);
    mid.remove("a"); // tombstone the only inherited key -> mid is observationally empty
    mid.freeze();
    assertTrue(mid.isEmpty());

    TagMap leaf = type.createFromParent(mid);
    assertEquals(0, leaf.size());
    assertTrue(leaf.isEmpty(), "isEmpty() must agree with size()==0");
    assertNull(leaf.getString("a"));
    assertTrue(collect(leaf).isEmpty());
    assertFalse(leaf.iterator().hasNext());
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void chainReadThroughMatchesEquivalentFlatMap(TagMapType type) {
    TagMap leaf = threeLevelLeaf(type);

    TagMap flat = type.create();
    flat.set("a", "gp-a");
    flat.set("b", "mid-b");
    flat.set("c", "leaf-c");
    flat.set("d", "mid-d");
    flat.set("e", "leaf-e");

    assertEquals(flat.size(), leaf.size());
    assertEquals(collect(flat), collect(leaf));
    assertEquals(flat.keySet(), leaf.keySet());
    for (String k : new String[] {"a", "b", "c", "d", "e", "missing"}) {
      assertEquals(flat.getString(k), leaf.getString(k), "mismatch for key " + k);
    }
  }

  // --- slice 6: put/getAndSet report the prior visible value, including inherited ---

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void putReturnsInheritedParentValueAsPrior(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type)); // parent {a, b}
    Object prior = child.put("a", "child-a"); // "a" exists only in the parent
    assertEquals("parent-a", prior, "put must report the inherited value as the previous mapping");
    assertEquals("child-a", child.getString("a")); // new value stored locally
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void putReturnsNullForAKeyInNeitherLocalNorParent(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    assertNull(child.put("brand-new", "v"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void putReturnsLocalPriorWhenShadowingParent(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("a", "local-a"); // local now shadows the parent
    assertEquals("local-a", child.put("a", "local-a2"), "the local prior wins over the parent's");
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void putAfterRemoveReportsNoPriorNotTheParentValue(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.remove("a"); // tombstone the parent's "a": no longer visible
    assertNull(child.put("a", "child-a"), "a tombstoned key had no visible prior value");
    assertEquals("child-a", child.getString("a"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void getAndSetReturnsInheritedEntryAsPrior(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    TagMap.Entry prior = child.getAndSet("b", "child-b");
    assertEquals("parent-b", prior.objectValue());
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void getAndSetByIdReturnsTheInheritedEntryOfThatId(TagMapType type) {
    TagMap parent = type.create();
    parent.set(KnownTags.HTTP_ROUTE_ID, "/parent/{id}");
    TagMap child = type.createFromParent(parent.freeze());

    TagMap.Entry prior =
        child.getAndSet(TagMap.Entry.create(KnownTags.HTTP_ROUTE_ID, (Object) "/child/{id}"));

    assertEquals("/parent/{id}", prior.objectValue());
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void setDoesNotReportPriorButStillClearsTombstone(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.remove("b"); // tombstone
    child.set("b", "child-b"); // void set: no prior lookup, but must clear the tombstone
    assertEquals("child-b", child.getString("b"));
  }

  // --- slice 7: clear() removes inherited mappings too (detaches the parent) ---

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void clearRemovesInheritedMappingsAndDetachesParent(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type)); // {a, b}
    child.set("c", "child-c");

    child.clear();

    assertTrue(child.isEmpty(), "clear must remove local AND inherited mappings");
    assertEquals(0, child.size());
    assertNull(child.getString("a")); // inherited no longer visible
    assertNull(child.getString("c"));
    assertFalse(child.containsKey("a"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void clearDoesNotAffectTheFrozenParent(TagMapType type) {
    TagMap parent = frozenParent(type);
    TagMap child = type.createFromParent(parent);
    child.clear();
    assertEquals("parent-a", parent.getString("a"), "the shared frozen parent is untouched");
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void putAfterClearBehavesAsAPlainMap(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.clear();
    child.set("x", "x-val");
    assertEquals(1, child.size());
    assertEquals("x-val", child.getString("x"));
    assertNull(child.getString("a"), "no read-through after clear detached the parent");
  }

  // --- slice 8: fillMap/fillStringMap materialize the visible union, not just local entries ---

  private static Map<String, Object> fillMapCollect(TagMap map) {
    Map<String, Object> out = new HashMap<>();
    map.fillMap(out);
    return out;
  }

  private static Map<String, String> fillStringMapCollect(TagMap map) {
    Map<String, String> out = new HashMap<>();
    map.fillStringMap(out);
    return out;
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void fillMapMaterializesDedupedUnionLocalWins(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type)); // parent {a, b}
    child.set("b", "child-b"); // shadows parent "b"
    child.set("c", "child-c");

    Map<String, Object> out = fillMapCollect(child);
    assertEquals(3, out.size(), "union {a, b, c} with b deduped");
    assertEquals("parent-a", out.get("a")); // inherited tag must not be dropped
    assertEquals("child-b", out.get("b")); // local wins
    assertEquals("child-c", out.get("c"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void fillMapHonorsTombstonedParentKeys(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("c", "child-c");
    child.remove("a"); // tombstone parent's "a"

    Map<String, Object> out = fillMapCollect(child);
    assertEquals(2, out.size());
    assertFalse(out.containsKey("a"));
    assertEquals("parent-b", out.get("b"));
    assertEquals("child-c", out.get("c"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void fillStringMapMaterializesDedupedUnionLocalWins(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.set("b", "child-b"); // shadows parent "b"
    child.set("c", "child-c");

    Map<String, String> out = fillStringMapCollect(child);
    assertEquals(3, out.size());
    assertEquals("parent-a", out.get("a")); // inherited tag preserved
    assertEquals("child-b", out.get("b"));
    assertEquals("child-c", out.get("c"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void fillStringMapHonorsTombstonedParentKeys(TagMapType type) {
    TagMap child = type.createFromParent(frozenParent(type));
    child.remove("a"); // tombstone parent's "a"

    Map<String, String> out = fillStringMapCollect(child);
    assertEquals(1, out.size());
    assertFalse(out.containsKey("a"));
    assertEquals("parent-b", out.get("b"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void fillMapAcrossChainMatchesEquivalentFlatMap(TagMapType type) {
    TagMap leaf = threeLevelLeaf(type);

    TagMap flat = type.create();
    flat.set("a", "gp-a");
    flat.set("b", "mid-b");
    flat.set("c", "leaf-c");
    flat.set("d", "mid-d");
    flat.set("e", "leaf-e");

    assertEquals(fillMapCollect(flat), fillMapCollect(leaf));
    assertEquals(fillStringMapCollect(flat), fillStringMapCollect(leaf));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  void fillMapWithoutParentFillsOnlyLocalEntries(TagMapType type) {
    // regression: the no-parent fast path is unchanged
    TagMap flat = type.create();
    flat.set("x", "x-val");
    flat.set("y", "y-val");

    Map<String, Object> out = fillMapCollect(flat);
    assertEquals(2, out.size());
    assertEquals("x-val", out.get("x"));
    assertEquals("y-val", out.get("y"));
  }
}
