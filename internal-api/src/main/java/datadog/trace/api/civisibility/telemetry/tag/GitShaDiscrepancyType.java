package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;
import java.util.Locale;

public enum GitShaDiscrepancyType implements TagValue {
  REPOSITORY_DISCREPANCY,
  COMMIT_DISCREPANCY;

  @Override
  public String asString() {
    return "type:" + name().toLowerCase(Locale.ROOT);
  }
}
