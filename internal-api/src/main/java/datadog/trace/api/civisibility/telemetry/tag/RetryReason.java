package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum RetryReason implements TagValue {
  atr("Auto Test Retries"),
  efd("Early Flakiness Detection");

  private final String s;
  private final String description;

  RetryReason(String description) {
    this.description = description;
    this.s = "retry_reason:" + name();
  }

  public String getDescription() {
    return description;
  }

  @Override
  public String asString() {
    return s;
  }
}
