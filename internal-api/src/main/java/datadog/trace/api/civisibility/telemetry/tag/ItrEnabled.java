package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/**
 * Whether remote settings response has ITR enabled (ITR can be enabled, while skipping is disabled
 * if we're running on a default branch)
 */
public enum ItrEnabled implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "itr_enabled:true";
  }
}
