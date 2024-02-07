package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** The type of test framework used */
public enum TestFramework implements TagValue {
  JUNIT4,
  JUNIT5,
  TESTNG,
  SPOCK,
  CUCUMBER,
  MUNIT,
  SCALATEST,
  KARATE;

  private final String s;

  TestFramework() {
    s = "test_framework:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
