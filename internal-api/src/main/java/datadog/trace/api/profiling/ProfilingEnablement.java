package datadog.trace.api.profiling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum ProfilingEnablement {
  ENABLED(true),
  DISABLED(false),
  AUTO(true);

  private static final Logger logger = LoggerFactory.getLogger(Profiling.class);

  private final boolean active;

  ProfilingEnablement(boolean active) {
    this.active = active;
  }

  public boolean isActive() {
    return active;
  }

  public static ProfilingEnablement of(String value) {
    if (value == null) {
      return DISABLED;
    }
    switch (value.toLowerCase()) {
      case "true":
      case "1":
        return ENABLED;
      case "auto":
        return AUTO;
      default:
        return DISABLED;
    }
  }

  public static void validate(String value) {
    if (value == null) {
      return;
    }
    switch (value.toLowerCase()) {
      case "false":
      case "true":
      case "auto":
        return;
        // values 1 and 0 are accepted for backwards compatibility
      case "1":
      case "0":
        return;
      default:
        logger.warn(
            "Invalid value for 'dd.profiling.enabled' (DD_PROFILING_ENABLED) detected: {}. Valid values are 'true', 'false' and 'auto'.",
            value);
    }
  }
}
