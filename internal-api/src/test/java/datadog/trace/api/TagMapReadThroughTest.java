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
import org.junit.jupiter.api.Test;

/**
 * Read-through support, slice 1 (read path): a child {@link TagMap} with a frozen parent reads
 * through to the parent on a local miss, while local entries shadow the parent (local-wins).
 * Removal/tombstones and bulk (iteration/serialize) union come in later slices.
 */
class TagMapReadThroughTest {
  private static TagMap frozenParent() {
    TagMap parent = TagMap.create();
    parent.set("a", "parent-a");
    parent.set("b", "parent-b");
    parent.freeze();
    return parent;
  }

  @Test
  void readsThroughToParentOnMiss() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set("c", "child-c");
    // miss locally -> read through
    assertEquals("parent-a", child.getString("a"));
    assertEquals("parent-b", child.getString("b"));
    // local
    assertEquals("child-c", child.getString("c"));
    assertNull(child.getString("missing"));
    assertTrue(child.containsKey("a"));
    assertFalse(child.containsKey("missing"));
  }

  @Test
  void localEntryShadowsParent() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // same key as parent
    child.set("b", "child-b");
    // local wins
    assertEquals("child-b", child.getString("b"));
    // parent still visible
    assertEquals("parent-a", child.getString("a"));
  }

  @Test
  void estimateSizeIsUpperBound() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // shadows parent "b"
    child.set("b", "child-b");
    child.set("c", "child-c");
    // true union = {a, b, c} = 3; estimate over-counts the shadowed "b": local 2 + parent 2 = 4
    assertEquals(4, child.estimateSize());
    assertTrue(child.estimateSize() >= 3, "estimateSize must be an upper bound on the true size");
  }

  @Test
  void emptinessSemantics() {
    TagMap emptyOverEmpty = TagMap.createFromParent(TagMap.create().freeze());
    assertTrue(emptyOverEmpty.isEmpty());
    assertTrue(emptyOverEmpty.isDefinitelyEmpty());

    TagMap emptyOverNonEmpty = TagMap.createFromParent(frozenParent());
    assertFalse(emptyOverNonEmpty.isEmpty(), "a non-empty parent makes the map non-empty");
    assertFalse(emptyOverNonEmpty.isDefinitelyEmpty());

    assertTrue((TagMap.create()).isDefinitelyEmpty());
  }

  @Test
  void parentMustBeFrozen() {
    TagMap mutableParent = TagMap.create();
    assertThrows(IllegalStateException.class, () -> TagMap.createFromParent(mutableParent));
  }

  @Test
  void emptyParentIsDroppedNotAttached() {
    TagMap emptyFrozen = TagMap.create();
    emptyFrozen.freeze();

    TagMap overEmpty = TagMap.createFromParent(emptyFrozen);
    // an empty frozen parent contributes nothing and never will -> dropped, no read-through cost
    assertNull(overEmpty.parent, "empty parent should be dropped");
    assertTrue(overEmpty.isDefinitelyEmpty());
    // still a normal mutable map
    overEmpty.set("x", "x-val");
    assertEquals("x-val", overEmpty.getString("x"));
    // a non-empty parent is still attached
    TagMap overNonEmpty = TagMap.createFromParent(frozenParent());
    assertNotNull(overNonEmpty.parent, "non-empty parent must be attached");
    assertEquals("parent-a", overNonEmpty.getString("a"));
  }

  // --- slice 2: removal / tombstones ---
  @Test
  void removingParentKeyHidesItFromChildButNotFromParent() {
    TagMap parent = frozenParent();
    TagMap child = TagMap.createFromParent(parent);
    // visible before removal
    assertEquals("parent-a", child.getString("a"));
    child.remove("a");
    // tombstoned: no longer reads through
    assertNull(child.getString("a"));
    assertFalse(child.containsKey("a"));
    // other parent keys unaffected
    assertEquals("parent-b", child.getString("b"));
    // frozen parent untouched
    assertEquals("parent-a", parent.getString("a"));
  }

  @Test
  void removeReturnsPriorVisibleValueViaParent() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // Map.remove contract: the key was present (via read-through), so removal reports it.
    assertTrue(child.remove("a"), "removing a parent-exposed key should report it was present");
    assertNull(child.getString("a"));
  }

  @Test
  void reSettingARemovedKeyRestoresVisibility() {
    TagMap child = TagMap.createFromParent(frozenParent());

    child.remove("a");
    assertNull(child.getString("a"));
    // re-set clears the tombstone
    child.set("a", "child-a");
    assertEquals("child-a", child.getString("a"));
  }

  @Test
  void removingAKeyThatIsBothLocalAndParentHidesBoth() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // shadows parent "b"
    child.set("b", "child-b");

    assertEquals("child-b", child.getString("b"));
    child.remove("b");

    assertNull(child.getString("b"), "removal must hide both the local entry and the parent's");
    // parent still has it
    assertEquals("parent-b", frozenParent().getString("b"));
  }

  // --- slice 3a: bulk forEach union + exact size/isEmpty ---
  private static Map<String, Object> collect(TagMap map) {
    Map<String, Object> out = new HashMap<>();
    map.forEach(e -> out.put(e.tag(), e.objectValue()));
    return out;
  }

  @Test
  void forEachEmitsDedupedUnionLocalWins() {
    // parent {a, b}
    TagMap child = TagMap.createFromParent(frozenParent());
    // shadows parent "b"
    child.set("b", "child-b");
    child.set("c", "child-c");

    Map<String, Object> u = collect(child);
    assertEquals(3, u.size(), "union {a, b, c} with b deduped");
    // read-through
    assertEquals("parent-a", u.get("a"));
    // local wins (no duplicate emit)
    assertEquals("child-b", u.get("b"));
    assertEquals("child-c", u.get("c"));
  }

  @Test
  void forEachSkipsTombstonedParentKeys() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set("c", "child-c");
    // tombstone parent's "a"
    child.remove("a");

    Map<String, Object> u = collect(child);
    assertEquals(2, u.size());
    assertFalse(u.containsKey("a"));
    assertEquals("parent-b", u.get("b"));
    assertEquals("child-c", u.get("c"));
  }

  @Test
  void biConsumerForEachAlsoEmitsUnion() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set("c", "child-c");

    Map<String, Object> out = new HashMap<>();
    // non-capturing: alloc-free path
    child.forEach(out, (m, e) -> m.put(e.tag(), e.objectValue()));
    assertEquals(3, out.size());
    assertEquals("parent-a", out.get("a"));
    assertEquals("child-c", out.get("c"));
  }

  @Test
  void sizeIsExactUnion() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // shadows
    child.set("b", "child-b");
    child.set("c", "child-c");
    // {a, b, c} — b deduped, not 4
    assertEquals(3, child.size());

    child.remove("a");
    // {b, c}
    assertEquals(2, child.size());
  }

  @Test
  void isEmptyExactWhenAllParentKeysTombstonedAndNoLocal() {
    // parent {a, b}
    TagMap child = TagMap.createFromParent(frozenParent());
    assertFalse(child.isEmpty());

    child.remove("a");
    child.remove("b");
    assertTrue(child.isEmpty(), "all parent keys tombstoned and no local entries -> empty");
    assertEquals(0, child.size());
  }

  // --- slice 3b: pull-based iterators / collection views ---
  @Test
  void iteratorEmitsDedupedUnion() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // shadows parent "b"
    child.set("b", "child-b");
    child.set("c", "child-c");

    Map<String, Object> u = new HashMap<>();
    Iterator<TagMap.EntryReader> it = child.iterator();
    while (it.hasNext()) {
      TagMap.EntryReader e = it.next();
      u.put(e.tag(), e.objectValue());
    }
    assertEquals(3, u.size());
    assertEquals("parent-a", u.get("a"));
    // local wins, emitted once
    assertEquals("child-b", u.get("b"));
    assertEquals("child-c", u.get("c"));
  }

  @Test
  void keySetReflectsUnionAndTombstones() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set("c", "child-c");

    Set<String> keys = child.keySet();
    // a, b, c
    assertEquals(3, keys.size());
    assertTrue(keys.contains("a"));
    assertTrue(keys.contains("c"));

    child.remove("a");
    assertEquals(2, child.keySet().size());
    assertFalse(child.keySet().contains("a"));
  }

  @Test
  void valuesAndEntrySetReflectUnion() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // shadows parent "b"
    child.set("b", "child-b");
    // {a, b} — b deduped
    assertEquals(2, child.entrySet().size());
    // local-won value
    assertTrue(child.values().contains("child-b"));
    assertTrue(child.values().contains("parent-a"));
    assertFalse(child.values().contains("parent-b"), "shadowed parent value must not appear");
  }

  // --- slice 3c: putAll from a read-through source copies the visible union, not just locals ---
  @Test
  void putAllFromReadThroughSourceCopiesFullVisibleUnion() {
    // parent {a, b}
    TagMap source = TagMap.createFromParent(frozenParent());
    // shadows parent b
    source.set("b", "child-b");
    source.set("c", "child-c");
    // empty -> putAllIntoEmptyMap path
    TagMap dest = TagMap.create();
    dest.putAll(source);
    // the parent-visible "a" must land too, not just source's local entries
    assertEquals(3, dest.size());
    assertEquals("parent-a", dest.getString("a"));
    // local-won value, deduped
    assertEquals("child-b", dest.getString("b"));
    assertEquals("child-c", dest.getString("c"));
  }

  @Test
  void putAllMergeFromReadThroughSourceCopiesVisibleUnion() {
    // {a, b}
    TagMap source = TagMap.createFromParent(frozenParent());

    TagMap dest = TagMap.create();
    // dest non-empty -> putAllMerge path
    dest.set("z", "dest-z");

    dest.putAll(source);
    // {a, b, z}
    assertEquals(3, dest.size());
    assertEquals("parent-a", dest.getString("a"));
    assertEquals("parent-b", dest.getString("b"));
    assertEquals("dest-z", dest.getString("z"));
  }

  @Test
  void putAllFromReadThroughSourceHonorsTombstones() {
    TagMap source = TagMap.createFromParent(frozenParent());
    // tombstone parent's "a"
    source.remove("a");

    TagMap dest = TagMap.create();
    dest.putAll(source);

    assertEquals(1, dest.size());
    assertFalse(dest.containsKey("a"), "tombstoned key must not be copied");
    assertEquals("parent-b", dest.getString("b"));
  }

  // --- slice 4: behavior-identical to a copy-down / flat map ---
  @Test
  void copyIsObservationallyIdentical() {
    // {a, b}
    TagMap child = TagMap.createFromParent(frozenParent());
    // shadows parent "b"
    child.set("b", "child-b");
    child.set("c", "child-c");

    TagMap copy = child.copy();
    assertEquals(child.size(), copy.size());
    // copy still reads through
    assertEquals("parent-a", copy.getString("a"));
    assertEquals("child-b", copy.getString("b"));
    assertEquals("child-c", copy.getString("c"));
    // same union
    assertEquals(collect(child), collect(copy));
  }

  @Test
  void copyIsIndependentlyMutable() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set("c", "child-c");

    TagMap copy = child.copy();
    // mutate copy's local
    copy.set("c", "copy-c");
    // tombstone on copy only
    copy.remove("a");

    assertEquals("child-c", child.getString("c"), "original unaffected by copy mutation");
    assertEquals("parent-a", child.getString("a"), "original still reads through a");
    assertEquals("copy-c", copy.getString("c"));
    assertNull(copy.getString("a"));
  }

  @Test
  void copyPreservesTombstones() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // tombstone "a"
    child.remove("a");

    TagMap copy = child.copy();
    assertNull(copy.getString("a"), "tombstone must carry into the copy");
    assertEquals("parent-b", copy.getString("b"));
  }

  /**
   * The contract that lets the consumer flip mergedTracerTags to a parent.
   */
  @Test
  void readThroughMatchesAnEquivalentFlatMap() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set("b", "child-b");
    child.set("c", "child-c");

    TagMap flat = TagMap.create();
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

  @Test
  void immutableCopyOfReadThroughIsFrozenAndStillReadsThrough() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set("c", "child-c");

    TagMap frozen = child.immutableCopy();
    assertTrue(frozen.isFrozen());
    // union preserved
    assertEquals("parent-a", frozen.getString("a"));
    assertEquals("child-c", frozen.getString("c"));
    // frozen blocks writes
    assertThrows(IllegalStateException.class, () -> frozen.set("x", "y"));
  }

  // --- slice 5: multi-level chains (baggage-style layering over more than one frozen parent) ---
  /**
   * Builds a 3-level chain leaf -&gt; mid -&gt; grandparent (both ancestors frozen) and returns the
   * leaf. Visible union, nearest-level-wins: {a=gp-a, b=mid-b, c=leaf-c, d=mid-d, e=leaf-e}.
   */
  private static TagMap threeLevelLeaf() {
    TagMap grandparent = TagMap.create();
    grandparent.set("a", "gp-a");
    grandparent.set("b", "gp-b");
    grandparent.set("c", "gp-c");
    grandparent.freeze();

    TagMap mid = TagMap.createFromParent(grandparent);
    // shadows grandparent b
    mid.set("b", "mid-b");
    mid.set("d", "mid-d");
    mid.freeze();

    TagMap leaf = TagMap.createFromParent(mid);
    // shadows grandparent c (mid doesn't define c)
    leaf.set("c", "leaf-c");
    leaf.set("e", "leaf-e");
    return leaf;
  }

  @Test
  void getWalksTheWholeChainNearestWins() {
    TagMap leaf = threeLevelLeaf();
    // only in grandparent (two levels up)
    assertEquals("gp-a", leaf.getString("a"));
    // mid shadows grandparent
    assertEquals("mid-b", leaf.getString("b"));
    // leaf shadows grandparent
    assertEquals("leaf-c", leaf.getString("c"));
    assertEquals("mid-d", leaf.getString("d"));
    assertEquals("leaf-e", leaf.getString("e"));
    assertNull(leaf.getString("missing"));
  }

  @Test
  void sizeIsExactUnionAcrossChain() {
    // {a, b, c, d, e}, shadowed duplicates deduped
    assertEquals(5, threeLevelLeaf().size());
  }

  @Test
  void forEachEmitsDedupedUnionAcrossChain() {
    Map<String, Object> u = collect(threeLevelLeaf());
    assertEquals(5, u.size());
    assertEquals("gp-a", u.get("a"));
    // nearest ancestor wins over grandparent
    assertEquals("mid-b", u.get("b"));
    // leaf wins
    assertEquals("leaf-c", u.get("c"));
    assertEquals("mid-d", u.get("d"));
    assertEquals("leaf-e", u.get("e"));
  }

  @Test
  void iteratorEmitsDedupedUnionAcrossChain() {
    Map<String, Object> u = new HashMap<>();
    Iterator<TagMap.EntryReader> it = threeLevelLeaf().iterator();
    while (it.hasNext()) {
      TagMap.EntryReader e = it.next();
      u.put(e.tag(), e.objectValue());
    }
    assertEquals(5, u.size());
    assertEquals("gp-a", u.get("a"));
    assertEquals("mid-b", u.get("b"));
    assertEquals("leaf-c", u.get("c"));
  }

  @Test
  void keySetReflectsChainUnion() {
    Set<String> keys = threeLevelLeaf().keySet();
    assertEquals(5, keys.size());
    for (String k : new String[] {"a", "b", "c", "d", "e"}) {
      assertTrue(keys.contains(k), "missing key " + k);
    }
  }

  @Test
  void leafTombstoneHidesGrandparentOnlyKey() {
    TagMap leaf = threeLevelLeaf();
    // "a" lives only in the grandparent, two levels up
    leaf.remove("a");
    assertNull(leaf.getString("a"));
    assertFalse(leaf.containsKey("a"));
    assertFalse(leaf.keySet().contains("a"));
    // {b, c, d, e}
    assertEquals(4, leaf.size());
  }

  @Test
  void intermediateAncestorTombstoneIsHonoredByBulkViews() {
    // mid removes an inherited grandparent key, THEN is frozen and reused as a parent. A non-leaf
    // level can therefore carry its own tombstones. Point lookups recurse through mid's tombstone
    // (correct); the bulk views must agree and not re-emit the key.
    TagMap grandparent = TagMap.create();
    grandparent.set("a", "gp-a");
    grandparent.set("b", "gp-b");
    grandparent.freeze();

    TagMap mid = TagMap.createFromParent(grandparent);
    // tombstone an inherited key before freezing
    mid.remove("a");
    mid.freeze();

    TagMap leaf = TagMap.createFromParent(mid);
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

  @Test
  void observationallyEmptyIntermediateParentIsDroppedNotAttached() {
    // A frozen mid level with no local entries whose every inherited key is tombstoned is
    // observationally empty even though a local (grandparent) level still holds entries.
    // createFromParent must drop it (exact isEmpty), so the leaf stays consistent: isEmpty() must
    // agree with size()/lookup/iteration.
    TagMap grandparent = TagMap.create();
    grandparent.set("a", "gp-a");
    grandparent.freeze();

    TagMap mid = TagMap.createFromParent(grandparent);
    // tombstone the only inherited key -> mid is observationally empty
    mid.remove("a");
    mid.freeze();
    assertTrue(mid.isEmpty());

    TagMap leaf = TagMap.createFromParent(mid);
    assertEquals(0, leaf.size());
    assertTrue(leaf.isEmpty(), "isEmpty() must agree with size()==0");
    assertNull(leaf.getString("a"));
    assertTrue(collect(leaf).isEmpty());
    assertFalse(leaf.iterator().hasNext());
  }

  @Test
  void chainReadThroughMatchesEquivalentFlatMap() {
    TagMap leaf = threeLevelLeaf();

    TagMap flat = TagMap.create();
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
  @Test
  void putReturnsInheritedParentValueAsPrior() {
    // parent {a, b}
    TagMap child = TagMap.createFromParent(frozenParent());
    // "a" exists only in the parent
    Object prior = child.put("a", "child-a");
    assertEquals("parent-a", prior, "put must report the inherited value as the previous mapping");
    // new value stored locally
    assertEquals("child-a", child.getString("a"));
  }

  @Test
  void putReturnsNullForAKeyInNeitherLocalNorParent() {
    TagMap child = TagMap.createFromParent(frozenParent());
    assertNull(child.put("brand-new", "v"));
  }

  @Test
  void putReturnsLocalPriorWhenShadowingParent() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // local now shadows the parent
    child.set("a", "local-a");
    assertEquals("local-a", child.put("a", "local-a2"), "the local prior wins over the parent's");
  }

  @Test
  void putAfterRemoveReportsNoPriorNotTheParentValue() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // tombstone the parent's "a": no longer visible
    child.remove("a");
    assertNull(child.put("a", "child-a"), "a tombstoned key had no visible prior value");
    assertEquals("child-a", child.getString("a"));
  }

  @Test
  void getAndSetReturnsInheritedEntryAsPrior() {
    TagMap child = TagMap.createFromParent(frozenParent());
    TagMap.Entry prior = child.getAndSet("b", "child-b");
    assertEquals("parent-b", prior.objectValue());
  }

  @Test
  void setDoesNotReportPriorButStillClearsTombstone() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // tombstone
    child.remove("b");
    // void set: no prior lookup, but must clear the tombstone
    child.set("b", "child-b");
    assertEquals("child-b", child.getString("b"));
  }

  // --- slice 7: clear() removes inherited mappings too (detaches the parent) ---
  @Test
  void clearRemovesInheritedMappingsAndDetachesParent() {
    // {a, b}
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set("c", "child-c");

    child.clear();

    assertTrue(child.isEmpty(), "clear must remove local AND inherited mappings");
    assertEquals(0, child.size());
    // inherited no longer visible
    assertNull(child.getString("a"));
    assertNull(child.getString("c"));
    assertFalse(child.containsKey("a"));
  }

  @Test
  void clearDoesNotAffectTheFrozenParent() {
    TagMap parent = frozenParent();
    TagMap child = TagMap.createFromParent(parent);
    child.clear();
    assertEquals("parent-a", parent.getString("a"), "the shared frozen parent is untouched");
  }

  @Test
  void putAfterClearBehavesAsAPlainMap() {
    TagMap child = TagMap.createFromParent(frozenParent());
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

  @Test
  void fillMapMaterializesDedupedUnionLocalWins() {
    // parent {a, b}
    TagMap child = TagMap.createFromParent(frozenParent());
    // shadows parent "b"
    child.set("b", "child-b");
    child.set("c", "child-c");

    Map<String, Object> out = fillMapCollect(child);
    assertEquals(3, out.size(), "union {a, b, c} with b deduped");
    // inherited tag must not be dropped
    assertEquals("parent-a", out.get("a"));
    // local wins
    assertEquals("child-b", out.get("b"));
    assertEquals("child-c", out.get("c"));
  }

  @Test
  void fillMapHonorsTombstonedParentKeys() {
    TagMap child = TagMap.createFromParent(frozenParent());
    child.set("c", "child-c");
    // tombstone parent's "a"
    child.remove("a");

    Map<String, Object> out = fillMapCollect(child);
    assertEquals(2, out.size());
    assertFalse(out.containsKey("a"));
    assertEquals("parent-b", out.get("b"));
    assertEquals("child-c", out.get("c"));
  }

  @Test
  void fillStringMapMaterializesDedupedUnionLocalWins() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // shadows parent "b"
    child.set("b", "child-b");
    child.set("c", "child-c");

    Map<String, String> out = fillStringMapCollect(child);
    assertEquals(3, out.size());
    // inherited tag preserved
    assertEquals("parent-a", out.get("a"));
    assertEquals("child-b", out.get("b"));
    assertEquals("child-c", out.get("c"));
  }

  @Test
  void fillStringMapHonorsTombstonedParentKeys() {
    TagMap child = TagMap.createFromParent(frozenParent());
    // tombstone parent's "a"
    child.remove("a");

    Map<String, String> out = fillStringMapCollect(child);
    assertEquals(1, out.size());
    assertFalse(out.containsKey("a"));
    assertEquals("parent-b", out.get("b"));
  }

  @Test
  void fillMapAcrossChainMatchesEquivalentFlatMap() {
    TagMap leaf = threeLevelLeaf();

    TagMap flat = TagMap.create();
    flat.set("a", "gp-a");
    flat.set("b", "mid-b");
    flat.set("c", "leaf-c");
    flat.set("d", "mid-d");
    flat.set("e", "leaf-e");

    assertEquals(fillMapCollect(flat), fillMapCollect(leaf));
    assertEquals(fillStringMapCollect(flat), fillStringMapCollect(leaf));
  }

  @Test
  void fillMapWithoutParentFillsOnlyLocalEntries() {
    // regression: the no-parent fast path is unchanged
    TagMap flat = TagMap.create();
    flat.set("x", "x-val");
    flat.set("y", "y-val");

    Map<String, Object> out = fillMapCollect(flat);
    assertEquals(2, out.size());
    assertEquals("x-val", out.get("x"));
    assertEquals("y-val", out.get("y"));
  }
}
