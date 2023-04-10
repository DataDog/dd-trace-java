package datadog.trace.instrumentation.vertx_4_0.server;

import datadog.trace.agent.tooling.muzzle.Reference;

// checks for vertx > 4
public class VertxVersionMatcher {
  // added in 4.0
  public static final Reference HTTP_1X_SERVER_RESPONSE =
      new Reference.Builder("io.vertx.core.http.impl.Http1xServerResponse").build();
}
