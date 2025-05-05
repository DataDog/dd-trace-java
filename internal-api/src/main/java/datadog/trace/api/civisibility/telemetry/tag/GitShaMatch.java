package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum GitShaMatch implements TagValue {
  TRUE,
  FALSE;

  private final String s;

  GitShaMatch() {
    s = "matched:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
