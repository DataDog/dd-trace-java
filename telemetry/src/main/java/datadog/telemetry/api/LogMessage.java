package datadog.telemetry.api;

public class LogMessage {
  private String message;
  private LogMessageLevel level;
  private String tags;
  private String stackTrace;
  private Integer tracerTime;

  public String getMessage() {
    return message;
  }

  public LogMessage message(String message) {
    this.message = message;
    return this;
  }

  public LogMessageLevel getLevel() {
    return level;
  }

  public LogMessage level(LogMessageLevel level) {
    this.level = level;
    return this;
  }

  public String getTags() {
    return tags;
  }

  public LogMessage tags(String tags) {
    this.tags = tags;
    return this;
  }

  public String getStackTrace() {
    return stackTrace;
  }

  public LogMessage stackTrace(String stackTrace) {
    this.stackTrace = stackTrace;
    return this;
  }

  public Integer getTracerTime() {
    return tracerTime;
  }

  public LogMessage tracerTime(Integer tracerTime) {
    this.tracerTime = tracerTime;
    return this;
  }
}
