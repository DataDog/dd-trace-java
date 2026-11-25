package datadog.trace.api.appsec.route;

import datadog.trace.api.Config;
import datadog.trace.api.KnownTags;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sets {@code _dd.appsec.normalized_route} (RFC-1103, RFC-1125) on the span that receives {@code
 * http.route}. Integrations call {@link #isEnabled()} before collecting any framework data, then
 * {@link #tag}. Never throws into the instrumented application.
 */
public final class NormalizedRouteHelper {

  public static final String NORMALIZED_ROUTE_TAG = KnownTags.DD_APPSEC_NORMALIZED_ROUTE_NAME;

  private static final Logger log = LoggerFactory.getLogger(NormalizedRouteHelper.class);

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
   * Renders the template and sets the tag on {@code span}. Only spans that already carry {@code
   * http.route} are tagged (RFC-1125 "No route"), so call it after the route is set. No-op when
   * disabled, when span or template is null, or when the route cannot be expressed; any error drops
   * the tag.
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
      if (span.getTag(Tags.HTTP_ROUTE) == null) {
        return;
      }
      String route = template.render(decodedPrefix, resolver);
      if (route != null && !route.isEmpty()) {
        span.setTag(KnownTags.DD_APPSEC_NORMALIZED_ROUTE, route);
      }
    } catch (Throwable t) {
      // never break the request: drop the tag
      log.debug("Failed to set the normalized route tag", t);
    }
  }
}
