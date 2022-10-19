package datadog.telemetry.api;

import datadog.trace.api.telemetry.TelemetryLogEntry;
import java.util.List;

public class LogTelemetry {
  @com.squareup.moshi.Json(name = "api_version")
  private ApiVersion apiVersion;

  @com.squareup.moshi.Json(name = "application")
  private Application application;

  @com.squareup.moshi.Json(name = "host")
  private Host host;

  @com.squareup.moshi.Json(name = "runtime_id")
  private String runtimeId;

  @com.squareup.moshi.Json(name = "seq_id")
  private Long seqId;

  @com.squareup.moshi.Json(name = "tracer_time")
  private Long tracerTime;

  @com.squareup.moshi.Json(name = "request_type")
  private RequestType requestType;

  @com.squareup.moshi.Json(name = "payload")
  private List<TelemetryLogEntry> payload;

  @com.squareup.moshi.Json(name = "debug")
  private boolean debug;

  public LogTelemetry apiVersion(ApiVersion apiVersion) {
    this.apiVersion = apiVersion;
    return this;
  }

  public LogTelemetry application(Application application) {
    this.application = application;
    return this;
  }

  public LogTelemetry host(Host host) {
    this.host = host;
    return this;
  }

  public LogTelemetry runtimeId(String runtimeId) {
    this.runtimeId = runtimeId;
    return this;
  }

  public LogTelemetry seqId(Long seqId) {
    this.seqId = seqId;
    return this;
  }

  public LogTelemetry tracerTime(Long tracerTime) {
    this.tracerTime = tracerTime;
    return this;
  }

  public LogTelemetry requestType(RequestType requestType) {
    this.requestType = requestType;
    return this;
  }

  public LogTelemetry debug(boolean debug) {
    this.debug = debug;
    return this;
  }

  public LogTelemetry payload(List<TelemetryLogEntry> payload) {
    this.payload = payload;
    return this;
  }

  /** Create a string representation of this pojo. */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class LogTelemetry {\n");

    sb.append("    apiVersion: ").append(apiVersion).append("\n");
    sb.append("    application: ").append(application).append("\n");
    sb.append("    host: ").append(host).append("\n");
    sb.append("    runtimeId: ").append(runtimeId).append("\n");
    sb.append("    seqId: ").append(seqId).append("\n");
    sb.append("    tracerTime: ").append(tracerTime).append("\n");
    sb.append("    requestType: ").append(requestType).append("\n");
    sb.append("    payload: ").append(payload).append("\n");
    sb.append("}");
    return sb.toString();
  }
}
