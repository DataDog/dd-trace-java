package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;
import java.util.Locale;

public enum EarlyFlakeDetectionAbortReason implements TagValue {
  FAULTY,
  SLOW;

  private final String s;

  EarlyFlakeDetectionAbortReason() {
    s = "early_flake_detection_abort_reason:" + name().toLowerCase(Locale.ROOT);
  }

  @Override
  public String asString() {
    return s;
  }
}
