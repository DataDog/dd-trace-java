package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum IsQuarantined implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "is_quarantined:true";
  }
}
