package datadog.trace.api.iast.telemetry;

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
}
