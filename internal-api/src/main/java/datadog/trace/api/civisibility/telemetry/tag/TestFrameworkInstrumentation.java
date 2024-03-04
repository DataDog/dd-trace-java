package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** The name of test framework instrumentation used */
public enum TestFrameworkInstrumentation implements TagValue {
  JUNIT4,
  JUNIT5,
  TESTNG,
  SPOCK,
  CUCUMBER,
  MUNIT,
  SCALATEST,
  KARATE,
  OTHER;

  private final String s;

  TestFrameworkInstrumentation() {
    s = "test_framework:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
