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
      case "pekko-http-server":
        prefix = "pekko-http";
        break;
      case "netty":
      case "finatra":
      case "axway-http":
        prefix = component;
        break;
      case "spray-http-server":
        prefix = "spray-http";
        break;
      case "restlet-http-server":
        prefix = "restlet-http";
        break;
      case "synapse-server":
        prefix = "synapse";
        break;
      default:
        prefix = "servlet";
        break;
    }
    return prefix + ".request";
  }
}
