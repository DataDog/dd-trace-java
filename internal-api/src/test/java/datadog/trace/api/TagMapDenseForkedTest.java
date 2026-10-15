package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * TagMap with the dense-store flag on: what only the flag decides. The flag is read once into a
 * static final, so this runs in its own JVM ({@code ForkedTest}) with the flag set before TagMap
 * first checks it. The dense store's behavior is covered by {@link DenseTagMapTest} and the shared
 * suites, which build through {@link TagMapType#DENSE}.
 */
class TagMapDenseForkedTest {
  static {
    System.setProperty("dd.trace.experimental.dense.tags.enabled", "true");
  }

  // slotted, span-level
  static final String COMPONENT = KnownTags.COMPONENT_NAME;
  // custom
  static final String CUSTOM_A = "custom.tag.a";

  private static int knownCount(TagMap map) {
    try {
      Field f = DenseTagMap.class.getDeclaredField("knownCount");
      f.setAccessible(true);
      return f.getInt(map);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void onlyTheDenseImplementationIsLoaded() {
    TagMap map = TagMap.create();
    map.set(KnownTags.COMPONENT_ID, "okhttp");

    assertSame(DenseTagMap.class, map.getClass());
    assertSame(DenseTagMap.class, TagMap.EMPTY.getClass());
    assertFalse(TagMapImplementations.isLoaded("datadog.trace.api.HashTagMap"));
  }

  @Test
  void aLedgerBuildsADenseMap() {
    TagMap map = TagMap.ledger().set(COMPONENT, "web").set(CUSTOM_A, "alpha").buildImmutable();

    assertEquals(1, knownCount(map));
    assertEquals("web", map.getObject(COMPONENT));
    assertEquals("alpha", map.getObject(CUSTOM_A));
    assertThrows(IllegalStateException.class, () -> map.set(COMPONENT, "x"));
  }
}
