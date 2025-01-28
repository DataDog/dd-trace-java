package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether the definition of a test was modified. */
public enum IsModified implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "is_modified:true";
  }
}
