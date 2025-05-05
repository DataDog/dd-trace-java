package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum GitShaMatch implements TagValue {
  TRUE,
  FALSE;

  GitShaMatch() {}

  @Override
  public String asString() {
    return "matched:" + name().toLowerCase();
  }
}
