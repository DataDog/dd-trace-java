package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * With the dense store off, {@link SizingHintTable} hands out no hints and never allocates its
 * lanes, and the hash store ignores a hint. Runs in its own JVM so the flag is read as off.
 */
class SizingHintTableHashOnlyForkedTest {
  static {
    System.setProperty("dd.trace.experimental.dense.tags.enabled", "false");
  }

  @Test
  void noHintWhenTheDenseStoreIsOff() {
    assertFalse(IdStoreFactory.Default.DENSE);
    assertNull(SizingHintTable.hintFor("registry.off", true));
    assertNull(SizingHintTable.hintFor("registry.off", false));
    assertNull(SizingHintTable.hintFor(null, true));
    assertFalse(isLoaded("datadog.trace.api.SizingHintTable$Lanes"));
  }

  @Test
  void theHashStoreIgnoresAHint() {
    SizingHint hint = new SizingHint("registry.off.direct");
    IdStore store = IdStore.create(null, hint);
    store.set(KnownTags.COMPONENT_ID, "okhttp");
    store.set(KnownTags.DD_BASE_SERVICE_ID, "svc");
    store.recordSize();

    assertSame(HashIdStore.class, store.getClass());
    assertEquals(SizingHintTable.SEED_SIZE, hint.size, "recordSize is a no-op on the hash store");
    assertFalse(isLoaded("datadog.trace.api.DenseIdStore"));
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
