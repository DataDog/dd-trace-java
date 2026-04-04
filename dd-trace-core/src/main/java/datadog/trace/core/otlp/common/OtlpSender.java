package datadog.trace.core.otlp.common;

/** Sends chunks of OTLP data. */
public interface OtlpSender {
  void send(OtlpPayload payload);
}
