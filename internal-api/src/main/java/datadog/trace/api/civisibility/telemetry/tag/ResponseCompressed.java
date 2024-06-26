package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether response body is compressed. */
public enum ResponseCompressed implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "rs_compressed:true";
  }
}
