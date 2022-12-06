package datadog.trace.instrumentation.vertx_3_4.server;

import datadog.trace.agent.tooling.muzzle.Reference;

// checks for vertx >= 3.4, < 4
public class VertxVersionMatcher {
  // added in 3.4
  static final Reference PARSABLE_HEADER_VALUE =
      new Reference.Builder("io.vertx.ext.web.impl.ParsableHeaderValue").build();
  // removed in 4.0
  static final Reference VIRTUAL_HOST_HANDLER =
      new Reference.Builder("io.vertx.ext.web.handler.VirtualHostHandler").build();
}
