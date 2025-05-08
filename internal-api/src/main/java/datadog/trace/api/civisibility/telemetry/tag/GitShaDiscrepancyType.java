package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum GitShaDiscrepancyType implements TagValue {
  REPOSITORY_DISCREPANCY,
  COMMIT_DISCREPANCY;

  @Override
  public String asString() {
    return "type:" + name().toLowerCase();
  }
}
