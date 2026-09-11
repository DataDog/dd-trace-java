package com.datadog.profiling.uploader;

import static datadog.trace.api.ConfigDefaults.DEFAULT_OTLP_GRPC_PROFILES_ENDPOINT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_OTLP_HTTP_PROFILES_ENDPOINT;

import datadog.communication.otlp.OtlpGrpcSender;
import datadog.communication.otlp.OtlpHttpSender;
import datadog.communication.otlp.OtlpSender;
import datadog.trace.api.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// mirrors OtlpMetricsSenderFactory and OtlpLogsService
final class OtlpProfilesSenderFactory {
  private static final Logger log = LoggerFactory.getLogger(OtlpProfilesSenderFactory.class);

  private OtlpProfilesSenderFactory() {}

  static OtlpSender create(Config config) {
    switch (config.getOtlpProfilesProtocol()) {
      case GRPC:
        return new OtlpGrpcSender(
            config.getOtlpProfilesEndpoint(),
            "/" + DEFAULT_OTLP_GRPC_PROFILES_ENDPOINT,
            config.getOtlpProfilesHeaders(),
            config.getOtlpProfilesTimeout(),
            config.getOtlpProfilesCompression());
      case HTTP_PROTOBUF:
        return new OtlpHttpSender(
            config.getOtlpProfilesEndpoint(),
            "/" + DEFAULT_OTLP_HTTP_PROFILES_ENDPOINT,
            config.getOtlpProfilesHeaders(),
            config.getOtlpProfilesTimeout(),
            config.getOtlpProfilesCompression());
      case HTTP_JSON:
        log.warn("OTLP profiles do not support JSON encoding; using HTTP_PROTOBUF transport");
        return new OtlpHttpSender(
            config.getOtlpProfilesEndpoint(),
            "/" + DEFAULT_OTLP_HTTP_PROFILES_ENDPOINT,
            config.getOtlpProfilesHeaders(),
            config.getOtlpProfilesTimeout(),
            config.getOtlpProfilesCompression());
      default:
        return null;
    }
  }
}
