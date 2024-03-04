package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether remote settings response has code coverage enabled */
public enum CoverageEnabled implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "coverage_enabled:true";
  }
}
