package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum HasFailedAllRetries implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "has_failed_all_retries:true";
  }
}
