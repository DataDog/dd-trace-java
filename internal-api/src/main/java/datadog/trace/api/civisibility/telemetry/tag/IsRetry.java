package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether a test case is a retry or not. */
public enum IsRetry implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "is_retry:true";
  }
}
