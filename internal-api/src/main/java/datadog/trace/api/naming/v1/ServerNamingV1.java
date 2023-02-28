package datadog.trace.api.naming.v1;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class ServerNamingV1 implements NamingSchema.ForServer {

  @Nonnull
  @Override
  public String operationForProtocol(@Nonnull String protocol) {
    return protocol + ".server.request";
  }

  @Nonnull
  @Override
  public String operationForComponent(@Nonnull String component) {
    return "http.server.request";
  }
}
