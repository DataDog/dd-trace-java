package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Erases the recorder's thread locals on the current thread, as the JDK erases all thread locals of
 * some pool workers each time they go idle.
 */
final class RecorderThreadLocals {

  private RecorderThreadLocals() {}

  static void erase() {
    int erased = 0;
    for (Field field : ProbeRecorder.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())
          && ThreadLocal.class.isAssignableFrom(field.getType())) {
        field.setAccessible(true);
        try {
          ((ThreadLocal<?>) field.get(null)).remove();
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        }
        erased++;
      }
    }
    assertTrue(erased > 0, "the recorder has no thread locals");
  }
}
