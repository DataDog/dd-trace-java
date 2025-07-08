package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** What kind of CI provider is running the test session. */
public enum Provider implements TagValue {
  APPVEYOR,
  AWS,
  AZP,
  BITBUCKET,
  BITRISE,
  BUILDKITE,
  CIRCLECI,
  CODEFRESH,
  GITHUBACTIONS,
  GITLAB,
  JENKINS,
  TEAMCITY,
  TRAVISCI,
  BUDDYCI,
  DRONE,
  UNSUPPORTED;

  private final String s;

  Provider() {
    s = "provider:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
