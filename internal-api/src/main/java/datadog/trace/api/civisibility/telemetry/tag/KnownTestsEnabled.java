package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum KnownTestsEnabled implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "known_tests_enabled:true";
  }
}
