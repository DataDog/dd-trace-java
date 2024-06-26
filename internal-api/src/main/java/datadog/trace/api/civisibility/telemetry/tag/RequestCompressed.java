package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether request body is compressed. */
public enum RequestCompressed implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "rq_compressed:true";
  }
}
