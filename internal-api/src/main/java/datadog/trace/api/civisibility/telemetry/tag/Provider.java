package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;
import java.util.Locale;

/** What kind of CI provider is running the test session. */
public enum Provider implements TagValue {
  APPVEYOR,
  AWS,
  AZP,
  BAZEL,
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
    s = "provider:" + name().toLowerCase(Locale.ROOT);
  }

  @Override
  public String asString() {
    return s;
  }
}
