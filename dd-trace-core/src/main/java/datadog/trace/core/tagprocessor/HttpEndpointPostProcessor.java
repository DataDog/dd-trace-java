package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;

import datadog.trace.api.TagMap;
import datadog.trace.api.endpoint.EndpointResolver;
import datadog.trace.bootstrap.instrumentation.api.WritableSpanLinks;
import datadog.trace.core.DDSpanContext;
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
      TagMap unsafeTags, DDSpanContext spanContext, WritableSpanLinks spanLinks) {
    if (!endpointResolver.isEnabled()) {
      log.debug("EndpointResolver is not enabled, skipping HTTP endpoint post processing");
      return;
    }

    if (unsafeTags.getObject(HTTP_METHOD) == null) {
      return;
    }

    try {
      String httpRoute = unsafeTags.getString(HTTP_ROUTE);
      String httpUrl = unsafeTags.getString(HTTP_URL);
      endpointResolver.resolveEndpoint(unsafeTags, httpRoute, httpUrl);
    } catch (Throwable t) {
      log.debug("Error processing HTTP endpoint for span {}", spanContext.getSpanId(), t);
    }
  }
}
