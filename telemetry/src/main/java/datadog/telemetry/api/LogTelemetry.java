package datadog.telemetry.api;

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
  private List<Log> payload;

  @com.squareup.moshi.Json(name = "debug")
  private boolean debug;

  /**
   * Get apiVersion
   *
   * @return apiVersion
   */
  public ApiVersion getApiVersion() {
    return apiVersion;
  }

  /** Set apiVersion */
  public void setApiVersion(ApiVersion apiVersion) {
    this.apiVersion = apiVersion;
  }

  public LogTelemetry apiVersion(ApiVersion apiVersion) {
    this.apiVersion = apiVersion;
    return this;
  }

  /**
   * Get application
   *
   * @return application
   */
  public Application getApplication() {
    return application;
  }

  /** Set application */
  public void setApplication(Application application) {
    this.application = application;
  }

  public LogTelemetry application(Application application) {
    this.application = application;
    return this;
  }

  /**
   * Get host
   *
   * @return host
   */
  public Host getHost() {
    return host;
  }

  /** Set host */
  public void setHost(Host host) {
    this.host = host;
  }

  public LogTelemetry host(Host host) {
    this.host = host;
    return this;
  }

  /**
   * Get runtimeId
   *
   * @return runtimeId
   */
  public String getRuntimeId() {
    return runtimeId;
  }

  /** Set runtimeId */
  public void setRuntimeId(String runtimeId) {
    this.runtimeId = runtimeId;
  }

  public LogTelemetry runtimeId(String runtimeId) {
    this.runtimeId = runtimeId;
    return this;
  }

  /**
   * Get seqId
   *
   * @return seqId
   */
  public Long getSeqId() {
    return seqId;
  }

  /** Set seqId */
  public void setSeqId(Long seqId) {
    this.seqId = seqId;
  }

  public LogTelemetry seqId(Long seqId) {
    this.seqId = seqId;
    return this;
  }

  /**
   * Get tracerTime
   *
   * @return tracerTime
   */
  public Long getTracerTime() {
    return tracerTime;
  }

  /** Set tracerTime */
  public void setTracerTime(Long tracerTime) {
    this.tracerTime = tracerTime;
  }

  public LogTelemetry tracerTime(Long tracerTime) {
    this.tracerTime = tracerTime;
    return this;
  }

  /**
   * Get requestType
   *
   * @return requestType
   */
  public RequestType getRequestType() {
    return requestType;
  }

  /** Set requestType */
  public void setRequestType(RequestType requestType) {
    this.requestType = requestType;
  }

  public LogTelemetry requestType(RequestType requestType) {
    this.requestType = requestType;
    return this;
  }

  /**
   * Get debug
   *
   * @return debug
   */
  public boolean getDebug() {
    return debug;
  }

  /** Set apiVersion */
  public void setDebug(boolean debug) {
    this.debug = debug;
  }

  public LogTelemetry debug(boolean debug) {
    this.debug = debug;
    return this;
  }

  /**
   * Get payload
   *
   * @return payload
   */
  public List<Log> getPayload() {
    return payload;
  }

  /** Set payload */
  public void setPayload(List<Log>payload) {
    this.payload = payload;
  }

  public LogTelemetry payload(List<Log>payload) {
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
