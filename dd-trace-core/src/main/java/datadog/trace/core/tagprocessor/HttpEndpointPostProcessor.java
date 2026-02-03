package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;

import datadog.trace.api.TagMap;
import datadog.trace.api.normalize.HttpResourceNames;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.endpoint.EndpointResolver;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-processes HTTP spans to update resource names based on inferred endpoints.
 *
 * <p>This processor implements the trace resource renaming feature by:
 *
 * <ul>
 *   <li>Using EndpointResolver to determine the best endpoint (route or simplified URL)
 *   <li>Combining HTTP method with the endpoint to create a resource name (e.g., "GET
 *       /users/{param:int}")
 *   <li>Updating the span's resource name only when an endpoint is available
 * </ul>
 *
 * <p>The processor respects the endpoint resolution logic:
 *
 * <ul>
 *   <li>If alwaysSimplifiedEndpoint=true, always compute from URL
 *   <li>If http.route exists and is eligible, use it
 *   <li>Otherwise, compute simplified endpoint from URL
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

    String httpMethod = httpMethodObj.toString();
    String httpRoute = httpRouteObj != null ? httpRouteObj.toString() : null;
    String httpUrl = httpUrlObj != null ? httpUrlObj.toString() : null;

    // Resolve endpoint using EndpointResolver
    // Pass unsafeTags directly - it's safe to use at this point since span is finished
    String endpoint = endpointResolver.resolveEndpoint(unsafeTags, httpRoute, httpUrl);

    if (endpoint != null && !endpoint.isEmpty()) {
      // Combine method and endpoint into resource name using cached join
      CharSequence resourceName = HttpResourceNames.join(httpMethod, endpoint);
      spanContext.setResourceName(
          resourceName, ResourceNamePriorities.HTTP_SERVER_RESOURCE_RENAMING);

      log.debug(
          "Updated resource name to '{}' for span {} (method={}, route={}, url={})",
          resourceName,
          spanContext.getSpanId(),
          httpMethod,
          httpRoute,
          httpUrl);
    }
  }
}
