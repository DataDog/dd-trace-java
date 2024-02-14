package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether CODEOWNERS file could be located when executing a test session */
public enum HasCodeowner implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "has_codeowner:true";
  }
}
