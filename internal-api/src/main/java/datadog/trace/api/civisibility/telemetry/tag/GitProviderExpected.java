package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum GitProviderExpected implements TagValue {
  USER_SUPPLIED,
  CI_PROVIDER,
  LOCAL_GIT,
  GIT_CLIENT,
  EMBEDDED;

  @Override
  public String asString() {
    return "expected_provider:" + name().toLowerCase();
  }
}
