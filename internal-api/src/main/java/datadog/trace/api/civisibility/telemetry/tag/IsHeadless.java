package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/**
 * Whether a test session executes in "headless mode", meaning that only child processes that are
 * forked to execute tests are instrumented, and the parent build system process is not
 * instrumented.
 */
public enum IsHeadless implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "is_headless:true";
  }
}
