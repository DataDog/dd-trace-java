package datadog.crashtracking.dto;

import java.util.Objects;

public final class StackFrame {
  public final String file;
  public final Integer line;
  public final String function;

  public StackFrame(String file, Integer line, String function) {
    this.file = file;
    this.line = line;
    this.function = function;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StackFrame that = (StackFrame) o;
    return Objects.equals(file, that.file)
        && Objects.equals(line, that.line)
        && Objects.equals(function, that.function);
  }

  @Override
  public int hashCode() {
    return Objects.hash(file, line, function);
  }
}
