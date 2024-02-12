package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** The type of Git command that was executed. */
public enum Command implements TagValue {
  GET_REPOSITORY,
  GET_BRANCH,
  CHECK_SHALLOW,
  UNSHALLOW,
  GET_LOCAL_COMMITS,
  GET_OBJECTS,
  PACK_OBJECTS,
  OTHER;

  private final String s;

  Command() {
    s = "command:" + name().toLowerCase();
  }

  @Override
  public String asString() {
    return s;
  }
}
