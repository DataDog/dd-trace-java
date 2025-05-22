package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum GitProviderDiscrepant implements TagValue {
  USER_SUPPLIED,
  CI_PROVIDER,
  LOCAL_GIT,
  GIT_CLIENT,
  EMBEDDED;

  @Override
  public String asString() {
    return "discrepant_provider:" + name().toLowerCase();
  }
}
