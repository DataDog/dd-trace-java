package datadog.trace.core;

import static java.util.Arrays.asList;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/** A plain {@link AgentSpanEvent} implementation for tests. */
public final class TestSpanEvent implements AgentSpanEvent {
  private final String name;
  private final long timeUnixNano;
  private final Map<String, Object> attributes;

  public TestSpanEvent(String name, long timeUnixNano, Map<String, Object> attributes) {
    this.name = name;
    this.timeUnixNano = timeUnixNano;
    this.attributes = attributes;
  }

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

  @Override
  public String name() {
    return this.name;
  }

  @Override
  public long timeUnixNano() {
    return this.timeUnixNano;
  }

  @Override
  public Map<String, Object> attributes() {
    return this.attributes;
  }
}
