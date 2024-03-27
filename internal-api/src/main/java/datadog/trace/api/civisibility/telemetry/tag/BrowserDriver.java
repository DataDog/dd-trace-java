package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** What kind of driver a browser test case is using. */
public enum BrowserDriver implements TagValue {
  SELENIUM;

  @Override
  public String asString() {
    return "browser_driver:selenium";
  }
}
