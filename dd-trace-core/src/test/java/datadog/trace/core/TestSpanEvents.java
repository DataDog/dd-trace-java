package datadog.trace.core;

import static java.util.Arrays.asList;

import java.util.LinkedHashMap;
import java.util.Map;

/** Test data for {@link DDSpanEvent}. */
public final class TestSpanEvents {
  private TestSpanEvents() {}

  /**
   * Creates attributes covering every supported value type, in a stable iteration order.
   *
   * @return The attributes with a string, a long, a double, a boolean, and an array value.
   */
  public static Map<String, Object> typedAttributes() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("str", "value");
    attributes.put("int", 42L);
    attributes.put("double", 12.5d);
    attributes.put("bool", true);
    attributes.put("arr", asList("x", 7L, 2.5d, false));
    return attributes;
  }
}
