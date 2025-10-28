package com.datadog.debugger.agent;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.util.TagsHelper;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Stores status information of probes for a service */
public class ProbeStatus {
  @Json(name = "ddsource")
  private final String ddSource = "dd_debugger";

  private final String type = "diagnostic";

  private final String service;
  private final String message;
  private final long timestamp;

  @Json(name = "debugger")
  private final Diagnostics diagnostics;

  public ProbeStatus(String service, String message, Diagnostics diagnostics) {
    this(service, message, diagnostics, System.currentTimeMillis());
  }

  public ProbeStatus(String service, String message, Diagnostics diagnostics, long timestamp) {
    this.service = service;
    this.message = message;
    this.diagnostics = diagnostics;
    this.timestamp = timestamp;
  }

  public ProbeStatus withNewTimestamp(Instant now) {
    return new ProbeStatus(service, message, diagnostics, now.toEpochMilli());
  }

  public String getDdSource() {
    return ddSource;
  }

  public String getType() {
    return type;
  }

  public String getService() {
    return service;
  }

  public String getMessage() {
    return message;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public Diagnostics getDiagnostics() {
    return diagnostics;
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ProbeStatus that = (ProbeStatus) o;
    return Objects.equals(ddSource, that.ddSource)
        && Objects.equals(service, that.service)
        && Objects.equals(message, that.message)
        && Objects.equals(diagnostics, that.diagnostics);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(ddSource, service, message, diagnostics);
  }

  @Generated
  @Override
  public String toString() {
    return "ProbeDiagnosticMessage{"
        + "ddSource='"
        + ddSource
        + '\''
        + ", service='"
        + service
        + '\''
        + ", timestamp='"
        + Instant.ofEpochMilli(timestamp)
        + '\''
        + ", message='"
        + message
        + '\''
        + ", debugger="
        + diagnostics
        + '}';
  }

  /** Stores status information for a probe */
  public static class Diagnostics {

    private final String probeId;

    private final int probeVersion;

    private final String runtimeId;
    private final Status status;

    private final ProbeException exception;

    public Diagnostics(String probeId, String runtimeId, Status status, ProbeException exception) {
      ProbeId id = ProbeId.from(probeId);
      this.probeId = id.getId();
      this.probeVersion = id.getVersion();
      this.runtimeId = runtimeId;
      this.status = status;
      this.exception = exception;
    }

    public Diagnostics(ProbeId probeId, String runtimeId, Status status, ProbeException exception) {
      this.probeId = probeId != null ? probeId.getId() : null;
      this.probeVersion = probeId != null ? probeId.getVersion() : 0;
      this.runtimeId = runtimeId;
      this.status = status;
      this.exception = exception;
    }

    public ProbeId getProbeId() {
      return new ProbeId(probeId, probeVersion);
    }

    public Status getStatus() {
      return status;
    }

    public ProbeException getException() {
      return exception;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Diagnostics that = (Diagnostics) o;
      return probeVersion == that.probeVersion
          && Objects.equals(probeId, that.probeId)
          && Objects.equals(runtimeId, that.runtimeId)
          && status == that.status
          && Objects.equals(exception, that.exception);
    }

    @Override
    public int hashCode() {
      return Objects.hash(probeId, probeVersion, runtimeId, status, exception);
    }

    @Override
    public String toString() {
      return "Diagnostics{"
          + "probeId='"
          + probeId
          + '\''
          + ", probeVersion="
          + probeVersion
          + ", runtimeId='"
          + runtimeId
          + '\''
          + ", status="
          + status
          + ", exception="
          + exception
          + '}';
    }
  }

  /** Stores error information of a probe instrumentation */
  public static class ProbeException {
    private final String type;
    private final String message;
    private final List<CapturedStackFrame> stacktrace;

    public ProbeException(String type, String message, List<CapturedStackFrame> stacktrace) {
      this.type = type;
      this.message = message;
      this.stacktrace = stacktrace;
    }

    public String getType() {
      return type;
    }

    public String getMessage() {
      return message;
    }

    public List<CapturedStackFrame> getStacktrace() {
      return stacktrace;
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ProbeException that = (ProbeException) o;
      return type.equals(that.type)
          && Objects.equals(message, that.message)
          && Objects.equals(stacktrace, that.stacktrace);
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(type, message, stacktrace);
    }

    @Generated
    @Override
    public String toString() {
      return "ProbeException{"
          + "type='"
          + type
          + '\''
          + ", message='"
          + message
          + '\''
          + ", stacktrace="
          + stacktrace
          + '}';
    }
  }

  /** Defined the different statuses of a probe */
  public enum Status {
    RECEIVED,
    INSTALLED,
    EMITTING,
    BLOCKED,
    ERROR
  }

  public static class Builder {

    private final String serviceName;

    private final String runtimeId;

    public Builder(Config config) {
      this.serviceName = TagsHelper.sanitize(config.getServiceName());
      this.runtimeId = config.getRuntimeId();
    }

    public ProbeStatus receivedMessage(ProbeId probeId) {
      return new ProbeStatus(
          this.serviceName,
          "Received probe " + probeId + ".",
          new Diagnostics(probeId, runtimeId, Status.RECEIVED, null));
    }

    public ProbeStatus installedMessage(ProbeId probeId) {
      return new ProbeStatus(
          this.serviceName,
          "Installed probe " + probeId + ".",
          new Diagnostics(probeId, runtimeId, Status.INSTALLED, null));
    }

    public ProbeStatus emittingMessage(String probeId) {
      return new ProbeStatus(
          this.serviceName,
          "Probe " + probeId + " is emitting.",
          new Diagnostics(probeId, runtimeId, Status.EMITTING, null));
    }

    public ProbeStatus blockedMessage(ProbeId probeId) {
      return new ProbeStatus(
          this.serviceName,
          "Blocked probe " + probeId + ".",
          new Diagnostics(probeId, runtimeId, Status.BLOCKED, null));
    }

    public ProbeStatus errorMessage(ProbeId probeId, Throwable ex) {
      return new ProbeStatus(
          this.serviceName,
          "Error installing probe " + probeId + ".",
          new Diagnostics(
              probeId,
              runtimeId,
              Status.ERROR,
              new ProbeException(
                  ex.getClass().getTypeName(),
                  ex.getMessage(),
                  Arrays.stream(ex.getStackTrace())
                      .map(CapturedStackFrame::from)
                      .collect(Collectors.toList()))));
    }

    public ProbeStatus errorMessage(ProbeId probeId, String message) {
      return new ProbeStatus(
          this.serviceName,
          "Error installing probe " + probeId + ".",
          new Diagnostics(
              probeId,
              runtimeId,
              Status.ERROR,
              new ProbeException("NO_TYPE", message, Collections.emptyList())));
    }
  }

  public static class DiagnosticsFactory implements JsonAdapter.Factory {
    @Override
    public JsonAdapter<?> create(Type type, Set<? extends Annotation> set, Moshi moshi) {
      if (type.getTypeName().equals(Diagnostics.class.getTypeName())) {
        JsonAdapter<Diagnostics> delegate = moshi.nextAdapter(this, type, set);
        return new DiagnosticsWrapperAdapter(delegate);
      }
      return null;
    }
  }

  /** Handles Json (de)serialization of Diagnostics */
  static class DiagnosticsWrapperAdapter extends JsonAdapter<Diagnostics> {
    private final JsonAdapter<Diagnostics> delegate;

    public DiagnosticsWrapperAdapter(JsonAdapter<Diagnostics> delegate) {
      this.delegate = delegate;
    }

    @Override
    public Diagnostics fromJson(JsonReader jsonReader) throws IOException {
      Diagnostics diagnostics = null;
      jsonReader.beginObject();
      jsonReader.skipName();
      diagnostics = delegate.fromJson(jsonReader);
      jsonReader.endObject();
      return diagnostics;
    }

    @Override
    public void toJson(JsonWriter jsonWriter, ProbeStatus.Diagnostics diagnostics)
        throws IOException {
      jsonWriter.beginObject();
      jsonWriter.name("diagnostics");
      delegate.toJson(jsonWriter, diagnostics);
      jsonWriter.endObject();
    }
  }
}
