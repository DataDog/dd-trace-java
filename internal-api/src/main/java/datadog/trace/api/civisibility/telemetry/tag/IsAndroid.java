package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether a test module/session belongs to an Android project. */
public enum IsAndroid implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "is_android:true";
  }
}
