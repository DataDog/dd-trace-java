package datadog.trace.instrumentation.vertx_5_0.server;

import datadog.trace.agent.tooling.muzzle.Reference;

// checks for vertx > 5
public class VertxVersionMatcher {

  // added in 5.0
  public static final Reference HTTP_HEADERS_INTERNAL =
      new Reference.Builder("io.vertx.core.internal.http.HttpHeadersInternal").build();
}
