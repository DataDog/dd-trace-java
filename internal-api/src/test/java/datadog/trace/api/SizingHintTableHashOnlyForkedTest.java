package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * With the dense store off, {@link SizingHintTable} hands out no hints, never allocates its lanes,
 * and the hash-only map ignores a hint. Runs in its own JVM so the flag is read as off.
 */
class SizingHintTableHashOnlyForkedTest {
  static {
    System.setProperty("dd.trace.experimental.dense.tags.enabled", "false");
  }

  @Test
  void noHintWhenTheDenseStoreIsOff() {
    assertFalse(TagMap.DenseStore.ENABLED);
    assertNull(SizingHintTable.hintFor("registry.off", true));
    assertNull(SizingHintTable.hintFor("registry.off", false));
    assertNull(SizingHintTable.hintFor(null, true));
    assertFalse(TagMapImplementations.isLoaded("datadog.trace.api.SizingHintTable$Lanes"));
  }

  @Test
  void theHashOnlyMapIgnoresAHint() {
    SizingHint hint = new SizingHint("registry.off.direct");
    TagMap map = TagMap.create(hint);
    map.set(KnownTags.COMPONENT_ID, "okhttp");
    map.set(KnownTags.DD_BASE_SERVICE_ID, "svc");
    map.recordSize(hint);

    assertSame(HashTagMap.class, map.getClass());
    assertSame(
        HashTagMap.class, TagMap.createFromParent(map.freeze(), hint).getClass(), "read-through");
    assertEquals(
        SizingHintTable.SEED_SIZE, hint.size, "recordSize is a no-op without a dense store");
    assertFalse(TagMapImplementations.isLoaded("datadog.trace.api.DenseTagMap"));
  }
}
