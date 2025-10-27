package datadog.trace.api.profiling;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum ProfilingEnablement {
  ENABLED(true, "manual"),
  DISABLED(false),
  AUTO(true),
  INJECTED(true);

  private static final Logger logger = LoggerFactory.getLogger(Profiling.class);

  private final boolean active;
  private final String alias;

  ProfilingEnablement(boolean active) {
    this.active = active;
    this.alias = this.name().toLowerCase();
  }

  ProfilingEnablement(boolean active, String alias) {
    this.active = active;
    this.alias = alias;
  }

  public boolean isActive() {
    return active;
  }

  public String getAlias() {
    return alias;
  }

  public static ProfilingEnablement from(ConfigProvider config) {
    ProfilingEnablement ret = ProfilingEnablement.DISABLED;
    String value = config.getString(ProfilingConfig.PROFILING_ENABLED);
    if (value != null) {
      ret = of(value);
    }
    if (ret == DISABLED) {
      String ssi = config.getString("injection.enabled");
      ret = ssi != null && ssi.contains("profiler") ? INJECTED : DISABLED;
    }
    return ret;
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
