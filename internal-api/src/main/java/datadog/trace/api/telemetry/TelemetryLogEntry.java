package datadog.trace.api.telemetry;

import java.util.Objects;

public class TelemetryLogEntry {
  private Integer hashCode = null;
  private String message;
  private String level;
  private String tags;
  private String stack_trace;

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getLevel() {
    return level;
  }

  public void setLevel(String level) {
    this.level = level;
  }

  public String getTags() {
    return tags;
  }

  public void setTags(String tags) {
    this.tags = tags;
  }

  public String getStackTrace() {
    return stack_trace;
  }

  public void setStackTrace(String stackTrace) {
    this.stack_trace = stackTrace;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Log {\n");

    sb.append("    message: ").append(message).append("\n");
    sb.append("    level: ").append(level).append("\n");
    sb.append("    tags: ").append(tags).append("\n");
    sb.append("    stack_trace: ").append(stack_trace).append("\n");
    sb.append("}");
    return sb.toString();
  }

  @Override
  public int hashCode() {
    if (null == hashCode) {
      hashCode = Objects.hash(message, level, tags, stack_trace);
    }
    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof TelemetryLogEntry) {
      TelemetryLogEntry log2 = (TelemetryLogEntry) obj;
      return Objects.equals(this.message, log2.message)
          && Objects.equals(this.level, log2.level)
          && Objects.equals(this.stack_trace, log2.stack_trace)
          && Objects.equals(this.tags, log2.tags);
    } else {
      return false;
    }
  }
}
