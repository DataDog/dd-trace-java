package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * With the dense store off, TagMap is the hash-only implementation, and the dense one is never
 * loaded: with one TagMap subclass loaded, every TagMap call devirtualizes without a guard. Runs in
 * its own JVM, so no other test has loaded the dense implementation first.
 */
class TagMapHashOnlyForkedTest {
  static {
    System.setProperty("dd.trace.experimental.dense.tags.enabled", "false");
  }

  @Test
  void onlyTheHashOnlyImplementationIsLoaded() {
    TagMap map = TagMap.create();
    map.set(KnownTags.COMPONENT_ID, "okhttp");
    map.set("custom.tag", "value");
    TagMap copy = TagMap.createFromParent(map.freeze());

    assertSame(HashTagMap.class, map.getClass());
    assertSame(HashTagMap.class, copy.getClass());
    assertSame(HashTagMap.class, TagMap.EMPTY.getClass());
    assertFalse(TagMapImplementations.isLoaded("datadog.trace.api.DenseTagMap"));
  }
}
