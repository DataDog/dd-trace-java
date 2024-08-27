package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;
import javax.annotation.Nonnull;

/** The exit code of a shell command execution */
public enum ExitCode implements TagValue {
  CODE_MINUS_1("-1"),
  CODE_1("1"),
  CODE_2("2"),
  CODE_127("127"),
  CODE_128("128"),
  CODE_129("129"),
  EXECUTABLE_MISSING("missing"),
  CODE_UNKNOWN("unknown");

  private final String s;

  ExitCode(String code) {
    s = "exit_code:" + code;
  }

  @Override
  public String asString() {
    return s;
  }

  @Nonnull
  public static ExitCode from(int exitCode) {
    switch (exitCode) {
      case -1:
        return CODE_MINUS_1;
      case 1:
        return CODE_1;
      case 2:
        return CODE_2;
      case 127:
        return CODE_127;
      case 128:
        return CODE_128;
      case 129:
        return CODE_129;
      default:
        return CODE_UNKNOWN;
    }
  }
}
