package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;
import java.util.Locale;

/** The type of test event */
public enum EventType implements TagValue {
  TEST,
  SUITE,
  MODULE,
  SESSION;

  private final String s;

  EventType() {
    s = "event_type:" + name().toLowerCase(Locale.ROOT);
  }

  @Override
  public String asString() {
    return s;
  }
}
