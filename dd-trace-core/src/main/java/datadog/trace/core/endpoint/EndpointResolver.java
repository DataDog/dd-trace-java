package datadog.trace.core.endpoint;

import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ENDPOINT;

import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves HTTP endpoints for APM trace metrics by determining whether to use http.route or compute
 * http.endpoint from the URL.
 *
 * <p>This class implements the endpoint inference logic defined in the RFC-1051 for trace resource
 * renaming, including route eligibility checks and endpoint computation.
 */
public class EndpointResolver {
  private static final Logger log = LoggerFactory.getLogger(EndpointResolver.class);

  private final boolean enabled;
  private final boolean alwaysSimplifiedEndpoint;

  /**
   * Creates a new EndpointResolver with the given configuration.
   *
   * @param enabled whether endpoint inference is enabled
   * @param alwaysSimplifiedEndpoint whether to always compute endpoint even when route exists
   */
  public EndpointResolver(boolean enabled, boolean alwaysSimplifiedEndpoint) {
    this.enabled = enabled;
    this.alwaysSimplifiedEndpoint = alwaysSimplifiedEndpoint;
  }

  /**
   * Resolves the endpoint for a span and optionally tags it with http.endpoint.
   *
   * <p>Resolution logic:
   *
   * <ol>
   *   <li>If disabled, return null
   *   <li>If alwaysSimplifiedEndpoint=true, compute from URL and tag span
   *   <li>If http.route exists and is eligible, use it (no tagging)
   *   <li>Otherwise, compute from URL and tag span with http.endpoint
   * </ol>
   *
   * @param unsafeTags unsafe tag map. Using at this point, when the span is finished and there
   *     should not be anymore external interaction, should be considered safe
   * @param httpRoute the http.route tag value (may be null)
   * @param httpUrl the http.url tag value (may be null)
   * @return the resolved endpoint, or null if resolution fails
   */
  @Nullable
  public String resolveEndpoint(
      java.util.Map<String, Object> unsafeTags,
      @Nullable String httpRoute,
      @Nullable String httpUrl) {
    if (!enabled) {
      return null;
    }

    // If alwaysSimplifiedEndpoint is set, always compute and tag
    if (alwaysSimplifiedEndpoint) {
      String endpoint = computeEndpoint(httpUrl);
      if (endpoint != null) {
        unsafeTags.put(HTTP_ENDPOINT, endpoint);
      }
      return endpoint;
    }

    // If route exists and is eligible, use it
    if (isRouteEligible(httpRoute)) {
      return httpRoute;
    }

    // Compute endpoint from URL and tag the span
    String endpoint = computeEndpoint(httpUrl);
    if (endpoint != null) {
      unsafeTags.put(HTTP_ENDPOINT, endpoint);
    }
    return endpoint;
  }

  /**
   * Determines if an http.route is eligible for use as an endpoint.
   *
   * <p>A route is NOT eligible if it is null, empty, or a catch-all wildcard pattern. Catch-all
   * patterns (single or double wildcards) indicate instrumentation problems rather than actual
   * routes. Regex fallback patterns are considered eligible.
   *
   * @param route the http.route value to check
   * @return true if the route can be used as an endpoint
   */
  public static boolean isRouteEligible(@Nullable String route) {
    if (route == null || route.isEmpty()) {
      return false;
    }

    // Discard catch-all routes that indicate instrumentation problems
    if ("*".equals(route) || "*/*".equals(route)) {
      return false;
    }

    return true;
  }

  /**
   * Computes an endpoint from a URL using the simplification algorithm.
   *
   * @param url the http.url tag value
   * @return the computed endpoint, or null if URL is null/empty
   */
  @Nullable
  public static String computeEndpoint(@Nullable String url) {
    if (url == null || url.isEmpty()) {
      return null;
    }

    try {
      return EndpointSimplifier.simplifyUrl(url);
    } catch (Exception e) {
      log.debug("Failed to compute endpoint from URL: {}", url, e);
      return null;
    }
  }

  /**
   * Returns whether endpoint inference is enabled.
   *
   * @return true if enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Returns whether simplified endpoint computation is always used.
   *
   * @return true if alwaysSimplifiedEndpoint is set
   */
  public boolean isAlwaysSimplifiedEndpoint() {
    return alwaysSimplifiedEndpoint;
  }
}
