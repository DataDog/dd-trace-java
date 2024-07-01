package datadog.trace.api.profiling;

public enum ProfilingEnablement {
  ENABLED(true),
  DISABLED(false),
  AUTO(true);

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
}
