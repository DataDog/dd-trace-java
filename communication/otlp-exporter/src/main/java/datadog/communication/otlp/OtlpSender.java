package datadog.communication.otlp;

/**
 * Sends OTLP payloads to a configured endpoint. Shared by the tracer's OTLP exporters and the
 * profile exporter so they all reuse one transport implementation.
 */
public interface OtlpSender {
  OtlpResponse send(OtlpPayload payload);

  void shutdown();
}
