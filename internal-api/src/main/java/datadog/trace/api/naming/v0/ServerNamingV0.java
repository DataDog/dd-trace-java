package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class ServerNamingV0 implements NamingSchema.ForServer {
  @Nonnull
  @Override
  public String operationForProtocol(@Nonnull String protocol) {
    if ("grpc".equals(protocol)) {
      return "grpc.server";
    }
    return protocol + ".request";
  }

  @Nonnull
  @Override
  public String operationForComponent(@Nonnull String component) {
    final String prefix;
    switch (component) {
      case "undertow-http-server":
        prefix = "undertow-http";
        break;
      case "akka-http-server":
        prefix = "akka-http";
        break;
      case "finatra":
        prefix = "finatra";
        break;
      default:
        prefix = "servlet";
        break;
    }
    return prefix + ".request";
  }
}
