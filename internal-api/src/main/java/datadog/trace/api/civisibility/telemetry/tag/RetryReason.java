package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum RetryReason implements TagValue {
  atr("Auto Test Retries", "auto_test_retry"),
  efd("Early Flakiness Detection", "early_flake_detection"),
  attemptToFix("Attempt to Fix", "attempt_to_fix");

  private final String value;
  private final String description;

  RetryReason(String description, String value) {
    this.description = description;
    this.value = value;
  }

  public String getDescription() {
    return description;
  }

  @Override
  public String toString() {
    return value;
  }

  @Override
  public String asString() {
    return "retry_reason:" + value;
  }
}
