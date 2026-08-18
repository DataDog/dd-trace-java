package com.datadog.profiling.uploader;

import static datadog.trace.api.ConfigDefaults.DEFAULT_OTLP_GRPC_PROFILES_ENDPOINT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_OTLP_HTTP_PROFILES_ENDPOINT;
import static datadog.trace.api.config.OtlpConfig.Protocol.HTTP_JSON;

import datadog.communication.otlp.OtlpSender;
import datadog.communication.otlp.OtlpSenderFactory;
import datadog.trace.api.Config;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Selects the {@link OtlpSender} for the configured OTLP profiles protocol. Mirrors {@code
 * OtlpMetricsSenderFactory} and {@code OtlpLogsService}.
 */
final class OtlpProfilesSenderFactory {
  private static final Logger log = LoggerFactory.getLogger(OtlpProfilesSenderFactory.class);

  private OtlpProfilesSenderFactory() {}

  /**
   * Builds the sender for {@code config}'s OTLP profiles protocol, or {@code null} if the protocol
   * is unsupported. Callers must null-check the result, as {@code OtlpMetricsService} does for the
   * equivalent {@code OtlpMetricsSenderFactory.create()} call.
   */
  @Nullable
  static OtlpSender create(Config config) {
    if (config.getOtlpProfilesProtocol() == HTTP_JSON) {
      // Profiles are always protobuf; HTTP_JSON uses the same transport as HTTP_PROTOBUF.
      log.warn("OTLP profiles do not support JSON encoding; using HTTP_PROTOBUF transport");
    }
    return OtlpSenderFactory.create(
        config.getOtlpProfilesProtocol(),
        config.getOtlpProfilesEndpoint(),
        "/" + DEFAULT_OTLP_GRPC_PROFILES_ENDPOINT,
        "/" + DEFAULT_OTLP_HTTP_PROFILES_ENDPOINT,
        config.getOtlpProfilesHeaders(),
        config.getOtlpProfilesTimeout(),
        config.getOtlpProfilesCompression());
  }
}
