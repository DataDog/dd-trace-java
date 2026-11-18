package datadog.trace.core.otlp.common;

import datadog.trace.common.writer.RemoteApi;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;

/** Sends chunks of OTLP data. */
public interface OtlpSender {
  RemoteApi.Response send(OtlpPayload payload);

  /**
   * Returns the URL this sender exports to, whose host and port identify the network destination
   * ({@code server.address} and {@code server.port}); null when exporting over a unix domain
   * socket, whose URL is only a {@code localhost} placeholder.
   */
  @Nullable
  HttpUrl destination();

  void shutdown();
}
