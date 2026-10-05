package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether a test ran against an emulated Android SDK (e.g. under Robolectric). */
public enum IsAndroidEmulated implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "is_android_emulated:true";
  }
}
