package datadog.communication.otlp;

import datadog.trace.api.config.OtlpConfig.Compression;
import datadog.trace.api.config.OtlpConfig.Protocol;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Selects the {@link OtlpSender} implementation for a given OTLP protocol/endpoint combination.
 * Shared by every signal-specific factory (metrics, logs, profiles) so they all pick their
 * transport identically and stay in sync as protocols/endpoints evolve.
 */
public final class OtlpSenderFactory {
  private OtlpSenderFactory() {}

  /**
   * Builds the sender for the given protocol, or {@code null} if the protocol is unsupported.
   * Callers must null-check the result.
   */
  @Nullable
  public static OtlpSender create(
      Protocol protocol,
      String endpoint,
      String grpcSignalPath,
      String httpSignalPath,
      Map<String, String> headers,
      int timeoutMillis,
      Compression compression) {
    switch (protocol) {
      case GRPC:
        return new OtlpGrpcSender(endpoint, grpcSignalPath, headers, timeoutMillis, compression);
      case HTTP_PROTOBUF:
      case HTTP_JSON:
        return new OtlpHttpSender(endpoint, httpSignalPath, headers, timeoutMillis, compression);
      default:
        return null;
    }
  }
}
