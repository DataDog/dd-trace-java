package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;
import java.util.Locale;

/** The type of code-coverage library used */
public enum Library implements TagValue {
  CUSTOM,
  JACOCO;

  private final String s;

  Library() {
    s = "library:" + name().toLowerCase(Locale.ROOT);
  }

  @Override
  public String asString() {
    return s;
  }
}
