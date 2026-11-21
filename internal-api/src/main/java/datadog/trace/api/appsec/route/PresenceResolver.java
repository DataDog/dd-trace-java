package datadog.trace.api.appsec.route;

/**
 * Per-request source of presence for the optional parts of a {@link RouteTemplate}. It is asked
 * about optional parameters, catch-alls (always optional) and optional groups, in path order. Parts
 * inside an absent group are not asked about.
 */
public interface PresenceResolver {

  /** Every optional part is unknown: the fallback removes all of them. */
  PresenceResolver UNKNOWN =
      new PresenceResolver() {
        @Override
        public RoutePresence presenceOf(RoutePart.Param param) {
          return RoutePresence.UNKNOWN;
        }

        @Override
        public RoutePresence presenceOf(RoutePart.Group group) {
          return RoutePresence.UNKNOWN;
        }
      };

  /** Presence of an optional parameter or a catch-all. */
  RoutePresence presenceOf(RoutePart.Param param);

  /** Presence of an optional group (optional static text, optional segment, etc.). */
  RoutePresence presenceOf(RoutePart.Group group);
}
