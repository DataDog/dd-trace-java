package datadog.trace.logging;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class LoggingSettingsDescription {
  private LoggingSettingsDescription() {}

  private static volatile Map<String, Object> description = Collections.emptyMap();

  public static void setDescription(Map<String, Object> description) {
    LoggingSettingsDescription.description =
        Collections.unmodifiableMap(new HashMap<>(description));
  }

  public static Map<String, Object> getDescription() {
    return description;
  }
}
