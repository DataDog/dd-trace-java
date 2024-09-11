package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** The type of coverage collection error */
public enum CoverageErrorType implements TagValue {
  RECORD,
  PATH,
  CONCURRENCY;

  private final String s;

  CoverageErrorType() {
    s = "error_type:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
