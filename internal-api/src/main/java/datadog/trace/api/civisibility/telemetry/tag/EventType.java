package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** The type of test event */
public enum EventType implements TagValue {
  TEST,
  SUITE,
  MODULE,
  SESSION;

  private final String s;

  EventType() {
    s = "event_type:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
