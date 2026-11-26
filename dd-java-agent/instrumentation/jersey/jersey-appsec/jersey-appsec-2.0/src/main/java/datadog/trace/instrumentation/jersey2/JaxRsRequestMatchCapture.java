package datadog.trace.instrumentation.jersey2;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static datadog.trace.bootstrap.ContextStores.getContextStore;
import static datadog.trace.bootstrap.ContextStores.getContextStoreId;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.appsec.route.JaxRsRequestMatch;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores what Jersey matched for the current request under the local root span, where the JAX-RS
 * annotations decorators read it to tag {@code _dd.appsec.normalized_route}. The store id must
 * match the one the decorators use.
 */
public final class JaxRsRequestMatchCapture {

  private static final Logger log = LoggerFactory.getLogger(JaxRsRequestMatchCapture.class);

  private static final ContextStore<Object, Object> REQUEST_MATCHES =
      getContextStore(
          getContextStoreId(AgentSpan.class.getName(), JaxRsRequestMatch.class.getName()));

  private JaxRsRequestMatchCapture() {}

  /**
   * Stores nothing when the base URI or its raw path is unknown (no prefix, so no tag; an empty
   * path is the root prefix), or when a sub-resource locator already marked the request with {@link
   * JaxRsRequestMatch#SUBRESOURCE}.
   *
   * @param baseUri the request's base URI (what Jersey served before the matched path), or null
   * @param boundValues the path parameters Jersey bound, by name
   */
  public static void capture(URI baseUri, Map<String, ?> boundValues) {
    try {
      String rawBasePath = baseUri == null ? null : baseUri.getRawPath();
      AgentSpan span = rawBasePath == null ? null : activeSpan();
      AgentSpan localRoot = span == null ? null : span.getLocalRootSpan();
      if (localRoot == null || REQUEST_MATCHES.get(localRoot) == JaxRsRequestMatch.SUBRESOURCE) {
        return;
      }
      REQUEST_MATCHES.put(localRoot, JaxRsRequestMatch.of(rawBasePath, boundValues));
    } catch (Throwable t) {
      log.debug(EXCLUDE_TELEMETRY, "Failed to capture the JAX-RS request match", t);
    }
  }
}
