package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum HasCustomBuckets implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "has_custom_buckets:true";
  }
}
