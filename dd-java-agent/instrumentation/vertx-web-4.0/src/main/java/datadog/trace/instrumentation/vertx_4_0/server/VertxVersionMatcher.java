package datadog.trace.instrumentation.vertx_4_0.server;

import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;

// checks for vertx > 4
public class VertxVersionMatcher {
  // added in 4.0
  static final ReferenceMatcher INSTANCE =
      new ReferenceMatcher(new Reference.Builder("io.vertx.core.http.impl.Http1xServerResponse").build());
}
