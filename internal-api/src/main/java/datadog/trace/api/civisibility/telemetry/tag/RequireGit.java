package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether remote settings response has "wait for Git upload to finish" flag enabled */
public enum RequireGit implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "require_git:true";
  }
}
