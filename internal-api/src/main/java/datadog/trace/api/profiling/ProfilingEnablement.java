package datadog.trace.api.profiling;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

public enum ProfilingEnablement {
  ENABLED(true, "manual"),
  DISABLED(false),
  AUTO(true),
  INJECTED(true);

  private final boolean active;
  private final String source;

  ProfilingEnablement(boolean active) {
    this.active = active;
    this.source = this.name().toLowerCase();
  }

  ProfilingEnablement(boolean active, String source) {
    this.active = active;
    this.source = source;
  }

  public boolean isActive() {
    return active;
  }

  public String getSource() {
    return source;
  }

  public static ProfilingEnablement from(ConfigProvider config) {
    String value = config.getString(ProfilingConfig.PROFILING_ENABLED);
    if (value != null) {
      return of(value);
    }
    String ssi = config.getString("injection.enabled");
    return ssi != null && ssi.contains("profiler") ? INJECTED : DISABLED;
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
