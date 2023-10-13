package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class ClientNamingV0 implements NamingSchema.ForClient {

  @Nonnull
  @Override
  public String operationForProtocol(@Nonnull String protocol) {
    final String postfix;
    switch (protocol) {
      case "grpc":
        postfix = ".client";
        break;
      case "rmi":
        postfix = ".invoke";
        break;
      default:
        postfix = ".request";
    }

    return protocol + postfix;
  }

  @Nonnull
  @Override
  public String operationForComponent(@Nonnull String component) {
    switch (component) {
      case "play-ws":
      case "okhttp":
        return component + ".request";
      case "netty-client":
        return "netty.client.request";
      case "akka-http-client":
        return "akka-http.client.request";
      case "pekko-http-client":
        return "pekko-http.client.request";
      case "jax-rs.client":
        return "jax-rs.client.call";
      default:
        return "http.request";
    }
  }
}
