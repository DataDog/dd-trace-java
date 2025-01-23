package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum ImpactedTestsDetectionEnabled implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "impacted_tests_detection_enabled:true";
  }
}
