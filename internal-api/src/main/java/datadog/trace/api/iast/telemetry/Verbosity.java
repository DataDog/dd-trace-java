package datadog.trace.api.iast.telemetry;

import datadog.trace.api.Config;

public enum Verbosity {
  OFF,
  MANDATORY,
  INFORMATION,
  DEBUG;

  public boolean isEnabled(final Verbosity value) {
    return value.ordinal() <= ordinal();
  }

  public boolean isDebugEnabled() {
    return isEnabled(DEBUG);
  }

  public boolean isInformationEnabled() {
    return isEnabled(INFORMATION);
  }

  public boolean isMandatoryEnabled() {
    return isEnabled(MANDATORY);
  }

  public static Verbosity getLevel() {
    Config config = Config.get();
    if (!config.isTelemetryEnabled() || !config.isTelemetryMetricsEnabled()) {
      return Verbosity.OFF;
    } else {
      return config.getIastTelemetryVerbosity();
    }
  }
}
