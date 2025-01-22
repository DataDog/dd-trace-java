package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum RetryReason implements TagValue {
  ATR,
  EFD;

  private final String s;

  RetryReason() {
    s = "retry_reason:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
