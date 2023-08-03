package datadog.telemetry.api;

public class LogMessage {

  @com.squareup.moshi.Json(name = "message")
  private String message;

  @com.squareup.moshi.Json(name = "level")
  private LogMessageLevel level;

  @com.squareup.moshi.Json(name = "tags")
  private String tags;

  @com.squareup.moshi.Json(name = "stack_trace")
  private String stackTrace;

  @com.squareup.moshi.Json(name = "tracer_time")
  private Integer tracerTime;

  /**
   * Get messages
   *
   * @return messages
   */
  public String getMessage() {
    return message;
  }

  /** Set messages */
  public void setMessage(String message) {
    this.message = message;
  }

  public LogMessage message(String message) {
    this.message = message;
    return this;
  }

  /**
   * Get level
   *
   * @return level
   */
  public LogMessageLevel getLevel() {
    return level;
  }

  /** Set level */
  public void setLevel(LogMessageLevel level) {
    this.level = level;
  }

  public LogMessage level(LogMessageLevel level) {
    this.level = level;
    return this;
  }

  /**
   * Get tags
   *
   * @return tags
   */
  public String getTags() {
    return tags;
  }

  /** Set tags */
  public void setTags(String tags) {
    this.tags = tags;
  }

  public LogMessage tags(String tags) {
    this.tags = tags;
    return this;
  }

  /**
   * Get stackTrace
   *
   * @return stackTrace
   */
  public String getStackTrace() {
    return stackTrace;
  }

  /** Set stackTrace */
  public void setStackTrace(String stackTrace) {
    this.stackTrace = stackTrace;
  }

  public LogMessage stackTrace(String stackTrace) {
    this.stackTrace = stackTrace;
    return this;
  }

  /**
   * Get tracerTime
   *
   * @return tracerTime
   */
  public Integer getTracerTime() {
    return tracerTime;
  }

  /** Set tracerTime */
  public void setTracerTime(Integer tracerTime) {
    this.tracerTime = tracerTime;
  }

  public LogMessage tracerTime(Integer tracerTime) {
    this.tracerTime = tracerTime;
    return this;
  }

  /** Create a string representation of this pojo. */
  @Override
  public String toString() {
    final StringBuffer sb = new StringBuffer("LogMessage{");
    sb.append("message='").append(message).append('\'');
    sb.append(", level=").append(level);
    sb.append(", tags='").append(tags).append('\'');
    sb.append(", stackTrace='").append(stackTrace).append('\'');
    sb.append(", tracerTime=").append(tracerTime);
    sb.append('}');
    return sb.toString();
  }
}
