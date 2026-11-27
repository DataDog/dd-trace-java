package datadog.trace.api.appsec.route;

/** Presence of an optional route part in the current request (RFC-1125). */
public enum RoutePresence {
  /** The framework bound a non-empty value from the URL (or its match included the part). */
  PRESENT,
  /**
   * Not matched, matched an empty value, or the value only comes from a default. The part is
   * removed together with its separator (a catch-all takes its preceding {@code /} with it).
   */
  ABSENT,
  /**
   * Catch-all only: the framework matched only {@code /} characters (e.g. {@code /files/} for
   * {@code /files/{*path}}). The part is absent, but a whole-segment catch-all that is last in the
   * route keeps a single trailing {@code /} (RFC-1125 test 49). Elsewhere it renders as {@link
   * #ABSENT}.
   */
  ABSENT_TRAILING_SLASH,
  /** The framework does not expose it: the part is removed with its separator (fallback). */
  UNKNOWN;

  /** Presence of a regular optional parameter from its bound value: null or empty is absent. */
  public static RoutePresence ofBoundValue(String value) {
    return value == null || value.isEmpty() ? ABSENT : PRESENT;
  }

  /**
   * Presence of a catch-all from its bound value: null or empty is {@link #ABSENT}; only {@code /}
   * characters is {@link #ABSENT_TRAILING_SLASH}; anything else is {@link #PRESENT}.
   */
  public static RoutePresence ofCatchAllValue(String value) {
    if (value == null || value.isEmpty()) {
      return ABSENT;
    }
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) != '/') {
        return PRESENT;
      }
    }
    return ABSENT_TRAILING_SLASH;
  }
}
