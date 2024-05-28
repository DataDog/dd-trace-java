package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum EarlyFlakeDetectionEnabled implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "early_flake_detection_enabled:true";
  }
}
