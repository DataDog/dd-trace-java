package datadog.trace.bootstrap.instrumentation.api;

import java.util.Map;

public interface Attributes {
  /**
   * Gets the attributes as an immutable map.
   *
   * @return The attributes as an immutable map.
   */
  Map<String, String> asMap();

  /**
   * Checks whether the attributes are empty.
   *
   * @return {@code true} if the attributes are empty, {@code false} otherwise.
   */
  boolean isEmpty();
}
