package datadog.trace.core.otlp.common;

import datadog.trace.common.writer.RemoteApi;

/** Sends chunks of OTLP data. */
public interface OtlpSender {
  RemoteApi.Response send(OtlpPayload payload);

  void shutdown();
}
