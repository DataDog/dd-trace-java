package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum MismatchGitProvider implements TagValue {
  USER_SUPPLIED,
  CI_PROVIDER,
  LOCAL_GIT,
  GIT_CLIENT,
  EMBEDDED;

  private final String s;

  MismatchGitProvider() {
    s = "mismatch_provider:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
