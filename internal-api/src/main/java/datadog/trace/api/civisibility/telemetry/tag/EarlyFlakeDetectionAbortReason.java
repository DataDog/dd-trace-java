package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum EarlyFlakeDetectionAbortReason implements TagValue {
  FAULTY,
  SLOW;

  private final String s;

  EarlyFlakeDetectionAbortReason() {
    s = "early_flake_detection_abort_reason:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
