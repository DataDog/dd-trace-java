package com.datadog.debugger.agent;

import com.datadog.debugger.util.TagsHelper;
import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
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

  private final String service;
  private final String message;

  @Json(name = "debugger")
  private final Diagnostics diagnostics;

  public ProbeStatus(String service, String message, Diagnostics diagnostics) {
    this.service = service;
    this.message = message;
    this.diagnostics = diagnostics;
  }

  public String getDdSource() {
    return ddSource;
  }

  public String getService() {
    return service;
  }

  public String getMessage() {
    return message;
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
    return ddSource.equals(that.ddSource)
        && service.equals(that.service)
        && message.equals(that.message)
        && diagnostics.equals(that.diagnostics);
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
    private final Status status;

    private final ProbeException exception;

    public Diagnostics(String probeId, Status status, ProbeException exception) {
      this.probeId = probeId;
      this.status = status;
      this.exception = exception;
    }

    public String getProbeId() {
      return probeId;
    }

    public Status getStatus() {
      return status;
    }

    public ProbeException getException() {
      return exception;
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Diagnostics that = (Diagnostics) o;
      return probeId.equals(that.probeId)
          && status == that.status
          && Objects.equals(exception, that.exception);
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(probeId, status, exception);
    }

    @Generated
    @Override
    public String toString() {
      return "Diagnostics{"
          + "probeId='"
          + probeId
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
    BLOCKED,
    ERROR
  }

  public static class Builder {

    private final String serviceName;

    public Builder(Config config) {

      this.serviceName = TagsHelper.sanitize(config.getServiceName());
    }

    public ProbeStatus receivedMessage(String probeId) {
      return new ProbeStatus(
          this.serviceName,
          "Received probe " + probeId + ".",
          new Diagnostics(probeId, Status.RECEIVED, null));
    }

    public ProbeStatus installedMessage(String probeId) {
      return new ProbeStatus(
          this.serviceName,
          "Installed probe " + probeId + ".",
          new Diagnostics(probeId, Status.INSTALLED, null));
    }

    public ProbeStatus blockedMessage(String probeId) {
      return new ProbeStatus(
          this.serviceName,
          "Blocked probe " + probeId + ".",
          new Diagnostics(probeId, Status.BLOCKED, null));
    }

    public ProbeStatus errorMessage(String probeId, Throwable ex) {
      return new ProbeStatus(
          this.serviceName,
          "Error installing probe " + probeId + ".",
          new Diagnostics(
              probeId,
              Status.ERROR,
              new ProbeException(
                  ex.getClass().getName(),
                  ex.getMessage(),
                  Arrays.stream(ex.getStackTrace())
                      .map(CapturedStackFrame::from)
                      .collect(Collectors.toList()))));
    }

    public ProbeStatus errorMessage(String probeId, String message) {
      return new ProbeStatus(
          this.serviceName,
          "Error installing probe " + probeId + ".",
          new Diagnostics(
              probeId,
              Status.ERROR,
              new ProbeException("NO_TYPE", message, Collections.emptyList())));
    }
  }

  public static class DiagnosticsFactory implements JsonAdapter.Factory {
    @Override
    public JsonAdapter<?> create(Type type, Set<? extends Annotation> set, Moshi moshi) {
      if (type.getTypeName().equals(Diagnostics.class.getName())) {
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
