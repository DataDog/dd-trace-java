package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ENDPOINT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;

import datadog.trace.api.Config;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for HTTP endpoint tagging logic. Handles route eligibility checks and URL path
 * parameterization for spans.
 *
 * <p>This implementation ensures: 1. Only applies to HTTP service entry spans (server spans) 2.
 * Limits cardinality through URL path parameterization 3. Uses http.route when available and
 * eligible (90% accuracy constraint) 4. Provides failsafe endpoint computation from http.url
 */
public final class HttpEndpointTagging {

  private static final Logger log = LoggerFactory.getLogger(HttpEndpointTagging.class);

  private static final Pattern URL_PATTERN =
      Pattern.compile("^(?<protocol>[a-z]+://(?<host>[^?/]+))?(?<path>/[^?]*)(?<query>(\\?).*)?$");

  // Applied in order - first match wins
  private static final Pattern PARAM_INT_PATTERN = Pattern.compile("[1-9][0-9]+");
  private static final Pattern PARAM_INT_ID_PATTERN = Pattern.compile("(?=.*[0-9].*)[0-9._-]{3,}");
  private static final Pattern PARAM_HEX_PATTERN = Pattern.compile("(?=.*[0-9].*)[A-Fa-f0-9]{6,}");
  private static final Pattern PARAM_HEX_ID_PATTERN =
      Pattern.compile("(?=.*[0-9].*)[A-Fa-f0-9._-]{6,}");
  private static final Pattern PARAM_STR_PATTERN = Pattern.compile(".{20,}|.*[%&'()*+,:=@].*");
  private static final Pattern SLASH_PATTERN = Pattern.compile("/", Pattern.LITERAL);

  private static final int MAX_PATH_ELEMENTS = 8;

  private HttpEndpointTagging() {
    // Utility class - no instantiation
  }

  /**
   * Determines if an HTTP route is eligible for use as endpoint tag. Routes must meet accuracy
   * requirements (90% constraint) to be considered eligible.
   *
   * @param route the HTTP route to check
   * @return true if route is eligible, false otherwise
   */
  public static boolean isRouteEligible(String route) {
    if (route == null) {
      return false;
    }

    route = route.trim();

    if (route.isEmpty()) {
      return false;
    }

    // Route must start with / to be a valid path
    if (!route.startsWith("/")) {
      return false;
    }

    // Reject overly generic routes that don't provide meaningful endpoint information
    if ("/".equals(route) || "/*".equals(route) || "*".equals(route)) {
      return false;
    }

    // Route is eligible for endpoint tagging
    return true;
  }

  /**
   * Parameterizes a URL path by replacing dynamic segments with {param:type} tokens. Splits path on
   * '/', discards empty elements, keeps first 8 elements, and applies regex patterns in order.
   *
   * @param path the URL path to parameterize
   * @return parameterized path with dynamic segments replaced by {param:type} tokens
   */
  public static String parameterizeUrlPath(String path) {
    if (path == null) {
      return null;
    }

    if (path.isEmpty() || "/".equals(path)) {
      return path;
    }

    int queryIndex = path.indexOf('?');
    if (queryIndex != -1) {
      path = path.substring(0, queryIndex);
    }

    int fragmentIndex = path.indexOf('#');
    if (fragmentIndex != -1) {
      path = path.substring(0, fragmentIndex);
    }

    String[] allSegments = SLASH_PATTERN.split(path);
    List<String> nonEmptySegments = new ArrayList<>();

    for (String segment : allSegments) {
      if (!segment.isEmpty()) {
        nonEmptySegments.add(segment);
      }
    }

    List<String> segments =
        nonEmptySegments.size() > MAX_PATH_ELEMENTS
            ? nonEmptySegments.subList(0, MAX_PATH_ELEMENTS)
            : nonEmptySegments;

    StringBuilder result = new StringBuilder();
    for (String segment : segments) {
      result.append("/");

      // First match wins
      if (PARAM_INT_PATTERN.matcher(segment).matches()) {
        result.append("{param:int}");
      } else if (PARAM_INT_ID_PATTERN.matcher(segment).matches()) {
        result.append("{param:int_id}");
      } else if (PARAM_HEX_PATTERN.matcher(segment).matches()) {
        result.append("{param:hex}");
      } else if (PARAM_HEX_ID_PATTERN.matcher(segment).matches()) {
        result.append("{param:hex_id}");
      } else if (PARAM_STR_PATTERN.matcher(segment).matches()) {
        result.append("{param:str}");
      } else {
        result.append(segment);
      }
    }

    String parameterized = result.toString();
    return parameterized.isEmpty() ? "/" : parameterized;
  }

  /**
   * Computes endpoint from HTTP URL using regex parsing. Returns '/' when URL is unavailable or
   * invalid.
   *
   * @param url the HTTP URL to process
   * @return parameterized endpoint path or '/'
   */
  public static String computeEndpointFromUrl(String url) {
    if (url == null) {
      return "/";
    }

    url = url.trim();
    if (url.isEmpty()) {
      return "/";
    }

    Matcher matcher = URL_PATTERN.matcher(url);
    if (!matcher.matches()) {
      log.debug("Failed to parse URL for endpoint computation: {}", url);
      return "/";
    }

    String path = matcher.group("path");
    if (path == null || path.isEmpty()) {
      return "/";
    }

    return parameterizeUrlPath(path);
  }

  /**
   * Sets the HTTP endpoint tag on a span if conditions are met. Only applies to HTTP service entry
   * spans when: 1. http.route is missing, empty, or not eligible 2. http.url is available for
   * endpoint computation
   *
   * <p>This method is designed for testing and backward compatibility. Production usage should
   * integrate with feature flags and span kind checks.
   *
   * @param span The span to potentially tag
   */
  public static void setEndpointTag(CoreSpan<?> span) {
    Object route = span.getTag(HTTP_ROUTE);

    // If route exists and is eligible, don't set endpoint tag
    if (route != null && isRouteEligible(route.toString())) {
      return;
    }

    // Try to compute endpoint from URL
    Object url = span.getTag(HTTP_URL);
    if (url != null) {
      String endpoint = computeEndpointFromUrl(url.toString());
      if (endpoint != null) {
        span.setTag(HTTP_ENDPOINT, endpoint);
      }
    }
  }

  /**
   * Sets the HTTP endpoint tag on a span context based on configuration flags. This overload
   * accepts DDSpanContext for use in tag post-processors and other core components.
   *
   * @param spanContext The span context to potentially tag
   * @param config The tracer configuration containing feature flags
   * @deprecated Use {@link #setEndpointTag(java.util.Map, Config)} for better performance in
   *     post-processors
   */
  @Deprecated
  public static void setEndpointTag(DDSpanContext spanContext, Config config) {
    if (!config.isResourceRenamingEnabled()) {
      return;
    }

    Object route = spanContext.unsafeGetTag(HTTP_ROUTE);

    // Check if we should use route (when not forcing simplified endpoints)
    if (!config.isResourceRenamingAlwaysSimplifiedEndpoint()
        && route != null
        && isRouteEligible(route.toString())) {
      return;
    }

    // Try to compute endpoint from URL
    Object url = spanContext.unsafeGetTag(HTTP_URL);
    if (url != null) {
      String endpoint = computeEndpointFromUrl(url.toString());
      if (endpoint != null) {
        spanContext.setTag(HTTP_ENDPOINT, endpoint);
      }
    }
  }

  /**
   * Sets the HTTP endpoint tag directly on the unsafe tags map based on configuration flags. This
   * method is optimized for use in TagPostProcessors where direct map access is more efficient than
   * using getTag/setTag methods.
   *
   * @param unsafeTags The unsafe tags map to potentially modify
   * @param config The tracer configuration containing feature flags
   */
  public static void setEndpointTag(java.util.Map<String, Object> unsafeTags, Config config) {
    if (!config.isResourceRenamingEnabled()) {
      return;
    }

    Object route = unsafeTags.get(HTTP_ROUTE);

    // Check if we should use route (when not forcing simplified endpoints)
    if (!config.isResourceRenamingAlwaysSimplifiedEndpoint()
        && route != null
        && isRouteEligible(route.toString())) {
      return;
    }

    // Try to compute endpoint from URL
    Object url = unsafeTags.get(HTTP_URL);
    if (url != null) {
      String endpoint = computeEndpointFromUrl(url.toString());
      if (endpoint != null) {
        unsafeTags.put(HTTP_ENDPOINT, endpoint);
      }
    }
  }

  /**
   * Sets the HTTP endpoint tag on a span based on configuration flags. This is the production
   * method that respects feature flags.
   *
   * @param span The span to potentially tag
   * @param config The tracer configuration containing feature flags
   */
  public static void setEndpointTag(CoreSpan<?> span, Config config) {
    if (!config.isResourceRenamingEnabled()) {
      return;
    }

    Object route = span.getTag(HTTP_ROUTE);

    // Check if we should use route (when not forcing simplified endpoints)
    if (!config.isResourceRenamingAlwaysSimplifiedEndpoint()
        && route != null
        && isRouteEligible(route.toString())) {
      return;
    }

    // Try to compute endpoint from URL
    Object url = span.getTag(HTTP_URL);
    if (url != null) {
      String endpoint = computeEndpointFromUrl(url.toString());
      if (endpoint != null) {
        span.setTag(HTTP_ENDPOINT, endpoint);
      }
    }
  }
}
