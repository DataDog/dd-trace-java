package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum ShaMatch implements TagValue {
  TRUE,
  FALSE;

  private final String s;

  ShaMatch() {
    s = "matched:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
