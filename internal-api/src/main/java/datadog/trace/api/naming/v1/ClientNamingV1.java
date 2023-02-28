package datadog.trace.api.naming.v1;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class ClientNamingV1 implements NamingSchema.ForClient {

  @Nonnull
  private String normalizeProtocol(@Nonnull final String protocol) {
    switch (protocol) {
      case "http":
      case "https":
        return "http";
      case "ftp":
      case "ftps":
        return "ftp";
      default:
        return protocol;
    }
  }

  @Nonnull
  @Override
  public String operationForProtocol(@Nonnull String protocol) {
    return normalizeProtocol(protocol) + ".client.request";
  }

  @Nonnull
  @Override
  public String operationForComponent(@Nonnull String component) {
    return "http.client.request";
  }
}
