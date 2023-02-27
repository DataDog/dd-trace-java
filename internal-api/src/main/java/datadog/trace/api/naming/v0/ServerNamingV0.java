package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class ServerNamingV0 implements NamingSchema.ForServer {
  @Nonnull
  @Override
  public String operationForProtocol(@Nonnull String protocol) {
    // cases will be addressed in subsequent PRs (for grpc and rmi)
    return protocol + ".request";
  }

  @Nonnull
  @Override
  public String operationForComponent(@Nonnull String component) {
    // more cases will be added in subsequent PRs.
    // Defaulting to servlet.request since it's for the majority of http server instrumentations
    if ("undertow-http-server".equals(component)) {
      return "undertow-http.request";
    }
    return "servlet.request";
  }
}
