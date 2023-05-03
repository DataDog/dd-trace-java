package datadog.trace.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * These are the old propagation styles that have been deprecated in favor of the propagation styles
 * in {@code TracePropagationStyle}
 */
@Deprecated
public enum PropagationStyle {
  DATADOG(TracePropagationStyle.DATADOG),
  B3(TracePropagationStyle.B3SINGLE, TracePropagationStyle.B3MULTI),
  HAYSTACK(TracePropagationStyle.HAYSTACK),
  XRAY(TracePropagationStyle.XRAY);

  private final List<TracePropagationStyle> newStyles;

  PropagationStyle(TracePropagationStyle... newStyles) {
    this.newStyles = Collections.unmodifiableList(Arrays.asList(newStyles));
  }

  public List<TracePropagationStyle> getNewStyles() {
    return newStyles;
  }

  public static PropagationStyle valueOfConfigName(String configName) {
    return valueOf(configName.toUpperCase().trim());
  }
}
