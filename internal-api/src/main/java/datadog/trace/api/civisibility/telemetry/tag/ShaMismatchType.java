package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum ShaMismatchType implements TagValue {
  REPOSITORY_MISMATCH,
  COMMIT_MISMATCH;

  private final String s;

  ShaMismatchType() {
    s = "type:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
