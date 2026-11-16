package datadog.trace.api.appsec.route;

import datadog.trace.api.Config;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/**
 * Sets {@code _dd.appsec.normalized_route} (RFC-1103, RFC-1125) on the span that receives {@code
 * http.route}. Integrations call {@link #isEnabled()} before collecting any framework data, then
 * {@link #tag}. Never throws into the instrumented application.
 */
public final class NormalizedRouteHelper {

  public static final String NORMALIZED_ROUTE_TAG = "_dd.appsec.normalized_route";

  private NormalizedRouteHelper() {}

  /** True when AppSec is active and API Security is enabled. */
  public static boolean isEnabled() {
    return ActiveSubsystems.APPSEC_ACTIVE && Config.get().isApiSecurityEnabled();
  }

  /**
   * Decodes the {@code %XX} escapes of a raw URL path (e.g. a context path) as UTF-8, to pass it as
   * the decoded prefix of {@link #tag}. {@code +} is kept; invalid or truncated escapes are kept as
   * is. Returns the same instance when there is no {@code %}; null stays null.
   */
  public static String decodePath(String rawPath) {
    return RouteEncoding.decodePath(rawPath);
  }

  /**
   * Renders the template and sets the tag on {@code span}. No-op when disabled, when span or
   * template is null, or when the route cannot be expressed; any error drops the tag.
   *
   * @param decodedPrefix decoded mount prefix served before the route (e.g. {@code /ctx}), or null
   * @param resolver presence of optional parts, or null when unknown (they are removed)
   */
  public static void tag(
      AgentSpan span, RouteTemplate template, String decodedPrefix, PresenceResolver resolver) {
    tag(isEnabled(), span, template, decodedPrefix, resolver);
  }

  @VisibleForTesting
  static void tag(
      boolean enabled,
      AgentSpan span,
      RouteTemplate template,
      String decodedPrefix,
      PresenceResolver resolver) {
    if (!enabled || span == null || template == null) {
      return;
    }
    try {
      String route = template.render(decodedPrefix, resolver);
      if (route != null && !route.isEmpty()) {
        span.setTag(NORMALIZED_ROUTE_TAG, route);
      }
    } catch (Throwable ignored) {
      // never break the request: drop the tag
    }
  }
}
