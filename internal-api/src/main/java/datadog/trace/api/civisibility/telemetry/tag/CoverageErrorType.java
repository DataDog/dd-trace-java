package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;
import java.util.Locale;

/** The type of coverage collection error */
public enum CoverageErrorType implements TagValue {
  RECORD,
  PATH,
  CONCURRENCY;

  private final String s;

  CoverageErrorType() {
    s = "error_type:" + name().toLowerCase(Locale.ROOT);
  }

  @Override
  public String asString() {
    return s;
  }
}
