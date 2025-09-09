package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public abstract class FailedTestReplayEnabled {
  public enum SettingsMetric implements TagValue {
    TRUE;

    @Override
    public String asString() {
      return "failed_test_replay_enabled:true";
    }
  }

  public enum SessionMetric implements TagValue {
    TRUE;

    @Override
    public String asString() {
      return "has_failed_test_replay:true";
    }
  }

  public enum TestMetric implements TagValue {
    TRUE;

    @Override
    public String asString() {
      return "is_failed_test_replay_enabled:true";
    }
  }
}
