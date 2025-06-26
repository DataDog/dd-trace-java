package datadog.crashtracking.dto;

import java.util.Arrays;
import java.util.Objects;

public final class StackTrace {
  private static final String FORMAT = "CrashTrackerV1";

  public final String format = FORMAT;
  public final StackFrame[] frames;

  public StackTrace(StackFrame[] frames) {
    this.frames = frames;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StackTrace that = (StackTrace) o;
    return Objects.equals(format, that.format) && Objects.deepEquals(frames, that.frames);
  }

  @Override
  public int hashCode() {
    return Objects.hash(format, Arrays.hashCode(frames));
  }
}
