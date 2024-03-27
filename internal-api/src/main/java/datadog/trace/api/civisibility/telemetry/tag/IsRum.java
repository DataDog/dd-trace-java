package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether a test is a RUM test case. */
public enum IsRum implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "is_rum:true";
  }
}
