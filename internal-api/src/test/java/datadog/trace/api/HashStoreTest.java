package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.TagMap.Entry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A custom-tag store reads through to its parent, and a local removal hides the inherited tag. */
class HashStoreTest {
  private static final String A = "custom.a";
  private static final String B = "custom.b";
  private static final String C = "custom.c";

  @Test
  void storedTagsAreFound() {
    HashStore store = HashStore.create(null);

    store.store(A, "1");
    store.store(B, 2);

    assertEquals("1", store.getEntry(A).objectValue());
    assertEquals(2, store.getEntry(B).objectValue());
    assertEquals(2, store.size());
    store.checkIntegrity();
  }

  @Test
  void readsThroughToItsParent() {
    HashStore parent = parentWith(A, "parent");
    HashStore child = HashStore.create(parent);

    assertEquals("parent", child.getEntry(A).objectValue());
    assertEquals(1, child.size());
    assertFalse(child.isEmpty());
  }

  @Test
  void aLocalTagShadowsTheParents() {
    HashStore child = HashStore.create(parentWith(A, "parent"));

    child.store(A, "child");

    assertEquals("child", child.getEntry(A).objectValue());
    assertEquals(1, child.size());
    assertEquals(tags(A, "child"), visible(child));
  }

  @Test
  void removingAnInheritedTagHidesIt() {
    HashStore parent = parentWith(A, "parent");
    HashStore child = HashStore.create(parent);

    Entry removed = child.remove(A);

    assertSame(parent.getEntry(A), removed);
    assertNull(child.getEntry(A));
    assertEquals(0, child.size());
    assertTrue(child.isEmpty());
    assertEquals(tags(), visible(child));
    assertEquals("parent", parent.getEntry(A).objectValue());
  }

  @Test
  void getAndPutAfterRemovingAnInheritedTagReportsNoPriorValue() {
    HashStore child = HashStore.create(parentWith(A, "parent"));
    child.remove(A);

    Entry prior = child.getAndPut(Entry.create(A, "child"));

    assertNull(prior);
    assertEquals("child", child.getEntry(A).objectValue());
    assertEquals(1, child.size());
  }

  @Test
  void getAndPutReportsTheInheritedPriorValue() {
    HashStore parent = parentWith(A, "parent");
    HashStore child = HashStore.create(parent);

    Entry prior = child.getAndPut(Entry.create(A, "child"));

    assertSame(parent.getEntry(A), prior);
    assertEquals("child", child.getEntry(A).objectValue());
  }

  @Test
  void forEachSkipsShadowedAndRemovedParentTags() {
    HashStore parent = HashStore.create(null);
    parent.store(A, "parent-a");
    parent.store(B, "parent-b");
    HashStore child = HashStore.create(parent);
    child.store(B, "child-b");
    child.store(C, "child-c");
    child.remove(A);

    assertEquals(tags(B, "child-b", C, "child-c"), visible(child));
    assertEquals(2, child.size());
  }

  @Test
  void putAllLocalMergesAnotherStoresLocalTags() {
    HashStore target = HashStore.create(null);
    target.store(A, "old");
    HashStore source = HashStore.create(null);
    source.store(A, "new");
    source.store(B, "b");

    target.putAllLocal(source);

    assertEquals(tags(A, "new", B, "b"), visible(target));
    assertEquals(2, target.size());
    target.checkIntegrity();
  }

  @Test
  void typedStoresRoundTrip() {
    HashStore store = HashStore.create(null);

    store.store(A, true);
    store.store(B, 7L);
    store.store(C, 1.5f);
    store.store("custom.d", 2.5d);
    store.store("custom.e", (CharSequence) "chars");

    assertEquals(true, store.getEntry(A).objectValue());
    assertEquals(7L, store.getEntry(B).objectValue());
    assertEquals(1.5f, store.getEntry(C).objectValue());
    assertEquals(2.5d, store.getEntry("custom.d").objectValue());
    assertEquals("chars", store.getEntry("custom.e").objectValue().toString());
    store.checkIntegrity();
  }

  @Test
  void removingALocalTagWithNoParent() {
    HashStore store = HashStore.create(null);
    store.store(A, "1");

    assertEquals("1", store.remove(A).objectValue());
    assertNull(store.remove(A));
    assertEquals(0, store.size());
  }

  @Test
  void removingAnInheritedTagTwiceReportsItOnce() {
    HashStore child = HashStore.create(parentWith(A, "parent"));

    assertEquals("parent", child.remove(A).objectValue());
    assertNull(child.remove(A));
  }

  @Test
  void getAndPutReportsTheLocalPriorValue() {
    HashStore store = HashStore.create(null);
    store.store(A, "old");

    assertEquals("old", store.getAndPut(Entry.create(A, "new")).objectValue());
    assertNull(store.getAndPut(Entry.create(B, "b")));
  }

  @Test
  void sizesAcrossLevels() {
    HashStore parent = parentWith(A, "parent");
    HashStore child = HashStore.create(parent);
    assertFalse(child.isDefinitelyEmpty());
    assertEquals(1, child.estimateSize());

    HashStore empty = HashStore.create(HashStore.create(null));
    assertTrue(empty.isDefinitelyEmpty());
    assertTrue(empty.isEmpty());
    assertEquals(0, empty.estimateSize());
  }

  /** Enough parent tags to form bucket groups, so read-through walks group chains too. */
  @Test
  void readThroughWalksBucketGroups() {
    HashStore parent = HashStore.create(null);
    Map<String, Object> expected = new HashMap<>();
    for (int i = 0; i < 64; i++) {
      parent.store("custom.tag" + i, i);
      expected.put("custom.tag" + i, i);
    }
    HashStore child = HashStore.create(parent);
    child.remove("custom.tag0");
    expected.remove("custom.tag0");
    child.store("custom.tag1", "local");
    expected.put("custom.tag1", "local");

    assertEquals(expected.size(), child.size());
    assertEquals(expected, visible(child));

    Map<String, Object> viaConsumer = new HashMap<>();
    child.forEach(entry -> viaConsumer.put(entry.tag(), entry.objectValue()));
    assertEquals(expected, viaConsumer);

    Map<String, Object> viaTwoContexts = new HashMap<>();
    child.forEach(
        viaTwoContexts, "-", (map, unused, entry) -> map.put(entry.tag(), entry.objectValue()));
    assertEquals(expected, viaTwoContexts);

    for (int i = 1; i < 64; i++) {
      child.remove("custom.tag" + i);
    }
    assertTrue(child.isEmpty());
  }

  @Test
  void aCopyIsIndependent() {
    HashStore child = HashStore.create(parentWith(A, "parent"));
    child.store(B, "b");
    child.remove(A);

    HashStore copy = child.copy();
    copy.store(A, "copy");
    copy.store(B, "copy-b");

    assertEquals(tags(B, "b"), visible(child));
    assertEquals(tags(A, "copy", B, "copy-b"), visible(copy));
  }

  @Test
  void putAllLocalHandlesEmptyStores() {
    HashStore target = HashStore.create(null);
    target.putAllLocal(HashStore.create(null));
    assertEquals(0, target.size());

    HashStore source = HashStore.create(null);
    source.store(A, "a");
    target.putAllLocal(source);
    assertEquals(tags(A, "a"), visible(target));
    target.checkIntegrity();
  }

  @Test
  void fillsMapsWithLocalTags() {
    HashStore store = HashStore.create(null);
    store.store(A, 1);

    Map<String, Object> objects = new HashMap<>();
    store.fillMapLocal(objects);
    Map<String, String> strings = new HashMap<>();
    store.fillStringMapLocal(strings);

    assertEquals(tags(A, 1), objects);
    assertEquals("1", strings.get(A));
    assertTrue(store.toInternalString().contains(A));
  }

  private static Map<String, Object> tags(Object... tagsAndValues) {
    Map<String, Object> tags = new HashMap<>();
    for (int i = 0; i < tagsAndValues.length; i += 2) {
      tags.put((String) tagsAndValues[i], tagsAndValues[i + 1]);
    }
    return tags;
  }

  private static HashStore parentWith(String tag, Object value) {
    HashStore parent = HashStore.create(null);
    parent.store(tag, value);
    return parent;
  }

  private static Map<String, Object> visible(HashStore store) {
    Map<String, Object> tags = new HashMap<>();
    store.forEach(tags, (map, entry) -> map.put(entry.tag(), entry.objectValue()));
    return tags;
  }
}
