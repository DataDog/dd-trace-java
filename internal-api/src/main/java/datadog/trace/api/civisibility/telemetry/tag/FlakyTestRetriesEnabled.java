package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum FlakyTestRetriesEnabled implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "flaky_test_retries_enabled:true";
  }
}
