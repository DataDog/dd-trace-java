package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** The exit code of a shell command execution */
public enum ExitCode implements TagValue {
  NON_ZERO;

  @Override
  public String asString() {
    return "exit_code:1";
  }
}
