package datadog.trace.core.otlp.sdk;

import static datadog.trace.core.otlp.sdk.OtelHealthMetrics.ORIGINS;
import static datadog.trace.core.otlp.sdk.OtelHealthMetrics.PARENT_ORIGIN;
import static java.util.Locale.ROOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.core.DDSpanContext;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards {@code OtelHealthMetrics.ORIGINS} and {@code started} against a new or reordered {@code
 * DDSpanContext.PARENT_ORIGIN_*} constant.
 */
class OtelHealthMetricsTest {
  private static final String PREFIX = "PARENT_ORIGIN_";
  private static final String FIX =
      "; update OtelHealthMetrics.ORIGINS and started to match the DDSpanContext."
          + PREFIX
          + "* constants";

  @Test
  void everyParentOriginHasItsOwnAdderAndAttribute() throws IllegalAccessException {
    List<Field> constants = parentOriginConstants();
    assertFalse(constants.isEmpty(), "no DDSpanContext." + PREFIX + "* constants found");
    assertEquals(
        ORIGINS.length,
        constants.size(),
        "number of " + PREFIX + "* constants differs from ORIGINS.length" + FIX);
    assertEquals(
        ORIGINS.length,
        OtelHealthMetrics.INSTANCE.started.length,
        "started.length differs from ORIGINS.length" + FIX);

    Map<Byte, String> seen = new HashMap<>();
    for (Field constant : constants) {
      String name = constant.getName();
      byte origin = constant.getByte(null);
      assertTrue(
          origin >= 0 && origin < ORIGINS.length,
          name + " = " + origin + " is outside [0, " + ORIGINS.length + ")" + FIX);
      String previous = seen.put(origin, name);
      assertNull(previous, name + " reuses the index of " + previous);
      String expected = name.substring(PREFIX.length()).toLowerCase(ROOT);
      assertEquals(
          expected,
          ORIGINS[origin].get(PARENT_ORIGIN),
          "ORIGINS[" + origin + "] must carry " + PARENT_ORIGIN + "=" + expected + FIX);
    }
  }

  private static List<Field> parentOriginConstants() {
    List<Field> constants = new ArrayList<>();
    for (Field field : DDSpanContext.class.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (field.getName().startsWith(PREFIX)
          && Modifier.isStatic(modifiers)
          && Modifier.isFinal(modifiers)
          && field.getType() == byte.class) {
        field.setAccessible(true);
        constants.add(field);
      }
    }
    return constants;
  }
}
