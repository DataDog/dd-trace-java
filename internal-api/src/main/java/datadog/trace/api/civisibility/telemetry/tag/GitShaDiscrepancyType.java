package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum GitShaDiscrepancyType implements TagValue {
  REPOSITORY_DISCREPANCY,
  COMMIT_DISCREPANCY;

  private final String s;

  GitShaDiscrepancyType() {
    s = "type:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
