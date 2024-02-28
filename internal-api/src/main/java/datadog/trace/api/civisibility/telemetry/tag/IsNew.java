package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether a test case is a new one. */
public enum IsNew implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "is_new:true";
  }
}
