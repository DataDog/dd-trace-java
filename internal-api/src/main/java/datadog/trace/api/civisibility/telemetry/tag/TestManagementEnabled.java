package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum TestManagementEnabled implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "test_management_enabled:true";
  }
}
