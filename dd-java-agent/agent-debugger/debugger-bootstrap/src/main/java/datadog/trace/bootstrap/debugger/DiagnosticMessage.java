package datadog.trace.bootstrap.debugger;

import java.time.Instant;
import java.util.Objects;

/** Stores status information of a probe and instrumentation result */
public final class DiagnosticMessage {
  public enum Kind {
    INFO,
    WARN,
    ERROR;
  }

  private final long timestamp;
  private final Kind kind;
  private final String message;
  private final Throwable throwable;

  public DiagnosticMessage(Kind kind, String message) {
    this(Instant.now().toEpochMilli(), kind, message, null);
  }

  public DiagnosticMessage(Kind kind, Throwable throwable) {
    this(Instant.now().toEpochMilli(), kind, null, throwable);
  }

  public DiagnosticMessage(long timestamp, Kind kind, String message, Throwable throwable) {
    this.timestamp = timestamp;
    this.kind = kind;
    this.message = message;
    this.throwable = throwable;
  }

  public Kind getKind() {
    return kind;
  }

  public String getMessage() {
    return message;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public Throwable getThrowable() {
    return throwable;
  }

  @Override
  public String toString() {
    return "DiagnosticMessage{"
        + "timestamp="
        + timestamp
        + ", kind="
        + kind
        + ", message='"
        + message
        + ", throwable='"
        + throwable
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DiagnosticMessage that = (DiagnosticMessage) o;
    return timestamp == that.timestamp
        && kind == that.kind
        && throwable == that.throwable
        && Objects.equals(message, that.message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(timestamp, kind, message, throwable);
  }
}
