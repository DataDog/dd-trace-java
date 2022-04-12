package datadog.trace.instrumentation.vertx_3_4.server;

import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;

// checks for vertx >= 3.4, < 4
public class VertxVersionMatcher {
  // added in 3.4
  static final ReferenceMatcher PARSABLE_HEADER_VALUE_MATCHER =
      new ReferenceMatcher(
          new Reference.Builder("io.vertx.ext.web.impl.ParsableHeaderValue").build());
  // removed in 4.0
  static final ReferenceMatcher VIRTUAL_HOST_HANDLER_MATCHER =
      new ReferenceMatcher(
          new Reference.Builder("io.vertx.ext.web.handler.VirtualHostHandler").build());

  static final IReferenceMatcher INSTANCE =
      new IReferenceMatcher.ConjunctionReferenceMatcher(
          PARSABLE_HEADER_VALUE_MATCHER, VIRTUAL_HOST_HANDLER_MATCHER);
}
