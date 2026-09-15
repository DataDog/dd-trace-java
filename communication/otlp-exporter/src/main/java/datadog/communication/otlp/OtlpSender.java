package datadog.communication.otlp;

/** Sends chunks of OTLP data. */
public interface OtlpSender {
  OtlpResponse send(OtlpPayload payload);

  void shutdown();
}
