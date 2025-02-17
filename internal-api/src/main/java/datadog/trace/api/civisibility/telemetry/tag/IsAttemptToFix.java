package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum IsAttemptToFix implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "is_attempt_to_fix:true";
  }
}
