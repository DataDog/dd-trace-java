package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;

import datadog.trace.api.TagMap;
import datadog.trace.api.endpoint.EndpointResolver;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-processes HTTP spans to resolve and tag the {@code http.endpoint} for APM trace metrics.
 *
 * <p>Implements the endpoint inference strategy from RFC-1051: tags the span with {@code
 * http.endpoint} (computed from the URL) so that stats buckets can be aggregated per endpoint. This
 * processor does <em>not</em> overwrite the span's resource name — resource renaming is the
 * backend's responsibility.
 *
 * <p>Resolution logic:
 *
 * <ul>
 *   <li>If {@code http.route} is eligible, use it as the endpoint (no {@code http.endpoint} tag
 *       added)
 *   <li>Otherwise, compute a simplified endpoint from {@code http.url} and tag the span with {@code
 *       http.endpoint}
 *   <li>If {@code alwaysSimplifiedEndpoint=true}, always compute from URL regardless of route
 * </ul>
 */
public class HttpEndpointPostProcessor extends TagsPostProcessor {
  private static final Logger log = LoggerFactory.getLogger(HttpEndpointPostProcessor.class);

  private final EndpointResolver endpointResolver;

  /** Creates a new HttpEndpointPostProcessor using the global config. */
  public HttpEndpointPostProcessor() {
    this(
        new EndpointResolver(
            datadog.trace.api.Config.get().isTraceResourceRenamingEnabled(),
            datadog.trace.api.Config.get().isTraceResourceRenamingAlwaysSimplifiedEndpoint()));
  }

  /**
   * Creates a new HttpEndpointPostProcessor with the given endpoint resolver.
   *
   * <p>Visible for testing.
   *
   * @param endpointResolver the resolver to use for endpoint inference
   */
  HttpEndpointPostProcessor(EndpointResolver endpointResolver) {
    this.endpointResolver = endpointResolver;
  }

  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    if (!endpointResolver.isEnabled()) {
      log.debug("EndpointResolver is not enabled, skipping HTTP endpoint post processing");
      return;
    }

    // Extract HTTP tags
    Object httpMethodObj = unsafeTags.get(HTTP_METHOD);
    Object httpRouteObj = unsafeTags.get(HTTP_ROUTE);
    Object httpUrlObj = unsafeTags.get(HTTP_URL);

    log.debug(
        "Processing tags for span {}: httpMethod={}, httpRoute={}, httpUrl={}",
        spanContext.getSpanId(),
        httpMethodObj,
        httpRouteObj,
        httpUrlObj);

    if (httpMethodObj == null) {
      // Not an HTTP span, skip processing
      log.debug("No HTTP method found, skipping HTTP endpoint post processing");
      return;
    }

    try {
      String httpMethod = httpMethodObj.toString();
      String httpRoute = httpRouteObj != null ? httpRouteObj.toString() : null;
      String httpUrl = httpUrlObj != null ? httpUrlObj.toString() : null;

      // Resolve endpoint using EndpointResolver
      // Pass unsafeTags directly - it's safe to use at this point since span is finished
      String endpoint = endpointResolver.resolveEndpoint(unsafeTags, httpRoute, httpUrl);

      if (endpoint != null) {
        log.debug(
            "Resolved endpoint '{}' for span {} (method={}, route={}, url={})",
            endpoint,
            spanContext.getSpanId(),
            httpMethod,
            httpRoute,
            httpUrl);
      }
    } catch (Throwable t) {
      log.debug("Error processing HTTP endpoint for span {}", spanContext.getSpanId(), t);
    }
  }
}
