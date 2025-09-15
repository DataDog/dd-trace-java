package datadog.trace.config.inversion;

import java.util.Locale;

/** Trace propagation styles for injecting and extracting trace propagation headers. */
public enum ConfigInversionStrictStyle {
  STRICT,
  WARNING,
  TEST;

  private String displayName;

  ConfigInversionStrictStyle() {
    this.displayName = name().toLowerCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    if (displayName == null) {
      displayName = name().toLowerCase(Locale.ROOT);
    }
    return displayName;
  }
}
