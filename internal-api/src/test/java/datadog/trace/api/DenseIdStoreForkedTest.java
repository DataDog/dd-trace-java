package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * The dense-store flag on: only {@link DenseIdStore} is loaded, so calls on an {@link IdStore} stay
 * monomorphic. The flag is read once into a static final, so this runs in its own JVM ({@code
 * ForkedTest}) with the flag set before anything reads it. The shared suites cover the dense
 * store's behavior when run with {@code DD_TRACE_EXPERIMENTAL_DENSE_TAGS_ENABLED=true}.
 */
class DenseIdStoreForkedTest {
  static {
    System.setProperty("dd.trace.experimental.dense.tags.enabled", "true");
  }

  @Test
  void onlyTheDenseStoreIsLoaded() {
    TagMap map = TagMap.create();
    map.set(KnownTags.COMPONENT_ID, "okhttp");
    map.set("custom.tag", "custom");

    assertSame(DenseIdStore.class, map.ids.getClass());
    assertSame(DenseIdStore.class, TagMap.EMPTY.ids.getClass());
    assertEquals("okhttp", map.getObject(KnownTags.COMPONENT_NAME));
    assertFalse(isLoaded("datadog.trace.api.HashIdStore"));
  }

  /** Whether {@code className} has been loaded by TagMap's class loader, without loading it. */
  private static boolean isLoaded(String className) {
    Method findLoadedClass;
    try {
      findLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
      findLoadedClass.setAccessible(true);
    } catch (RuntimeException | NoSuchMethodException e) {
      assumeTrue(false, "cannot inspect loaded classes on this JVM: " + e);
      return false;
    }
    try {
      return findLoadedClass.invoke(TagMap.class.getClassLoader(), className) != null;
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
