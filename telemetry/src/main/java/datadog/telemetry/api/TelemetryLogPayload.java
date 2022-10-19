package datadog.telemetry.api;

import datadog.trace.api.telemetry.TelemetryLogEntry;
import java.util.ArrayList;
import java.util.List;

public class TelemetryLogPayload extends Payload {

  @com.squareup.moshi.Json(name = "logs")
  private List<TelemetryLogEntry> exceptions = new ArrayList<>();

  /**
   * Get exceptions
   *
   * @return exceptions
   */
  public List<TelemetryLogEntry> getExceptions() {
    return exceptions;
  }

  /** Set exceptions */
  public void setExceptions(List<TelemetryLogEntry> exceptions) {
    this.exceptions = exceptions;
  }

  public TelemetryLogPayload exceptions(List<TelemetryLogEntry> exceptions) {
    this.exceptions = exceptions;
    return this;
  }

  public TelemetryLogPayload addExceptionItem(TelemetryLogEntry exceptionItem) {
    this.exceptions.add(exceptionItem);
    return this;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ExceptionsThrown {\n");
    sb.append("    ").append(super.toString()).append("\n");
    sb.append("    exceptions: ").append(exceptions).append("\n");
    sb.append("}");
    return sb.toString();
  }
}
