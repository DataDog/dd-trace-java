package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether remote settings response has ITR skipping enabled */
public enum ItrSkipEnabled implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "itrskip_enabled:true";
  }
}
