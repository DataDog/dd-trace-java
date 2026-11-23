package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;
import java.util.Locale;

public enum SkipReason implements TagValue {
  ITR("Skipped by Datadog Test Impact Analysis"),
  DISABLED("Flaky test is disabled by Datadog");

  private final String s;
  private final String description;

  SkipReason(String description) {
    this.description = description;
    this.s = "skip_reason:" + name().toLowerCase(Locale.ROOT);
  }

  public String getDescription() {
    return description;
  }

  @Override
  public String asString() {
    return s;
  }
}
