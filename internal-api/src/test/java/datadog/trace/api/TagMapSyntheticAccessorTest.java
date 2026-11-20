package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TagMap and its nested classes have no synthetic accessors. Targeting Java 8, javac reaches a
 * private member of a nested or outer class through a generated {@code access$NNN} method, or a
 * constructor taking a dummy {@code $1} class. C2 refuses to inline a call whose signature names a
 * never-loaded class, and every accessor adds a level of inlining depth, so on TagMap's hot paths a
 * member shared between nested classes must be package-private instead. Becomes a
 * {@code @NoSyntheticAccess} contract check (APMLP-1976).
 */
class TagMapSyntheticAccessorTest {
  @Test
  void theTagMapFamilyHasNoSyntheticAccessors() {
    List<Class<?>> classes = new ArrayList<>();
    for (Class<?> root : Arrays.asList(TagMap.class)) {
      collect(root, classes);
    }
    List<String> accessors = new ArrayList<>();
    for (Class<?> c : classes) {
      for (Method method : c.getDeclaredMethods()) {
        if (method.isSynthetic() && method.getName().startsWith("access$")) {
          accessors.add(method.toString());
        }
      }
      for (Constructor<?> constructor : c.getDeclaredConstructors()) {
        if (constructor.isSynthetic()) {
          accessors.add(constructor.toString());
        }
      }
    }
    assertEquals(new ArrayList<String>(), accessors);
  }

  private static void collect(Class<?> c, List<Class<?>> into) {
    into.add(c);
    for (Class<?> nested : c.getDeclaredClasses()) {
      collect(nested, into);
    }
  }
}
