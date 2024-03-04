package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** The type of endpoint where a request is sent */
public enum Endpoint implements TagValue {
  TEST_CYCLE,
  CODE_COVERAGE;

  private final String s;

  Endpoint() {
    s = "endpoint:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
