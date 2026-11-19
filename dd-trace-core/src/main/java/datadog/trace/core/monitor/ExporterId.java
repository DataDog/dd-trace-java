package datadog.trace.core.monitor;

import static datadog.trace.api.config.OtlpConfig.Protocol.GRPC;

import datadog.trace.api.config.OtlpConfig.Protocol;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;

/**
 * Identity of one exporter instance, passed to the {@link HealthMetrics} export hooks so a sink can
 * tell exporters apart. Build one per exporter and keep it: each new id takes the next {@link
 * ComponentId} sequence number, so a new id is a new series.
 */
public final class ExporterId {
  /** The telemetry signal an exporter sends. */
  public enum Signal {
    SPAN("span"),
    LOG("log"),
    METRIC_DATA_POINT("metric");

    final String componentSignal;

    Signal(String componentSignal) {
      this.componentSignal = componentSignal;
    }
  }

  private final Signal signal;
  private final ComponentId component;
  @Nullable private final String serverAddress;
  private final int serverPort;
  private final boolean grpc;

  /**
   * @param destination the export URL, whose host and port give {@link #serverAddress()} and {@link
   *     #serverPort()}; null when unknown or not meaningful, such as a unix domain socket
   * @param grpc whether the exporter speaks gRPC rather than HTTP
   */
  public ExporterId(
      Signal signal, ComponentId component, @Nullable HttpUrl destination, boolean grpc) {
    this.signal = signal;
    this.component = component;
    this.serverAddress = destination == null ? null : destination.host();
    this.serverPort = destination == null ? 0 : destination.port();
    this.grpc = grpc;
  }

  /**
   * Returns a new identity for an OTLP exporter sending {@code signal} over {@code protocol} to
   * {@code destination}, with the next {@link ComponentId} of its type.
   */
  public static ExporterId otlp(Signal signal, Protocol protocol, @Nullable HttpUrl destination) {
    return new ExporterId(
        signal,
        ComponentId.next(otlpExporterType(protocol, signal)),
        destination,
        protocol == GRPC);
  }

  /**
   * The {@code otel.component.type} of an OTLP exporter, e.g. {@code otlp_grpc_metric_exporter}.
   */
  static String otlpExporterType(Protocol protocol, Signal signal) {
    String transport;
    switch (protocol) {
      case GRPC:
        transport = "otlp_grpc_";
        break;
      case HTTP_JSON:
        transport = "otlp_http_json_";
        break;
      case HTTP_PROTOBUF:
      default:
        transport = "otlp_http_";
        break;
    }
    return transport + signal.componentSignal + "_exporter";
  }

  public Signal signal() {
    return signal;
  }

  public ComponentId component() {
    return component;
  }

  /** The destination host, or null when unknown. */
  @Nullable
  public String serverAddress() {
    return serverAddress;
  }

  /** The destination port, or 0 when unknown. */
  public int serverPort() {
    return serverPort;
  }

  public boolean grpc() {
    return grpc;
  }

  @Override
  public String toString() {
    return component.toString();
  }
}
