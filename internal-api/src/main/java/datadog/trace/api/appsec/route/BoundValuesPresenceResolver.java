package datadog.trace.api.appsec.route;

import java.util.List;
import java.util.Map;

/**
 * {@link PresenceResolver} backed by the path variables the framework bound for the current request
 * (e.g. Spring {@code URI_TEMPLATE_VARIABLES_ATTRIBUTE}).
 *
 * <ul>
 *   <li>Named parts: a null map means the framework did not expose them ({@link
 *       RoutePresence#UNKNOWN}); with a map, a missing key or a null value is absent. A {@link
 *       List} value uses its first element; other values use {@link String#valueOf(Object)}.
 *       Optional parameters use {@link RoutePresence#ofBoundValue}, catch-alls {@link
 *       RoutePresence#ofCatchAllValue}.
 *   <li>Unnamed catch-all ({@code **}): {@link RoutePresence#ofCatchAllValue} of the matcher
 *       remainder, only when {@link RouteTemplate#needsUnnamedCatchAllRemainder()}; otherwise
 *       {@link RoutePresence#UNKNOWN}.
 *   <li>Other unnamed optional parameters and groups: {@link RoutePresence#UNKNOWN}.
 * </ul>
 */
public final class BoundValuesPresenceResolver implements PresenceResolver {

  private final RoutePart.Param remainderCatchAll;
  private final Map<String, ?> boundValues;
  private final String unnamedCatchAllRemainder;

  private BoundValuesPresenceResolver(
      RoutePart.Param remainderCatchAll, Map<String, ?> boundValues, String remainder) {
    this.remainderCatchAll = remainderCatchAll;
    this.boundValues = boundValues;
    this.unnamedCatchAllRemainder = remainder;
  }

  /**
   * @param template the template being rendered (decides which catch-all may use the remainder)
   * @param boundValues every variable bound by the framework, or null when not exposed
   * @param unnamedCatchAllRemainder path matched by a trailing {@code **} ({@code /} when it
   *     matched only {@code /}), or null when unknown
   */
  public static PresenceResolver of(
      RouteTemplate template, Map<String, ?> boundValues, String unnamedCatchAllRemainder) {
    if (template == null || template.staticRoute() != null) {
      // a pre-rendered template never asks for presence
      return PresenceResolver.UNKNOWN;
    }
    RoutePart.Param remainderCatchAll =
        unnamedCatchAllRemainder == null ? null : template.trailingUnnamedCatchAll();
    if (boundValues == null && remainderCatchAll == null) {
      return PresenceResolver.UNKNOWN;
    }
    return new BoundValuesPresenceResolver(
        remainderCatchAll, boundValues, unnamedCatchAllRemainder);
  }

  /**
   * Framework-neutral variant taking the raw values a framework exposes.
   *
   * @param template the template being rendered
   * @param rawVars bound variables; used only when it is a {@link Map}
   * @param rawRemainder path the matcher extracted after the static part of the pattern, or null
   *     when not computed
   * @param lookupPathEndsWithSlash whether the path the matcher ran on ends with {@code /}:
   *     matchers return an empty remainder both for {@code /static} and {@code /static/}, so an
   *     empty remainder then means {@code **} matched only {@code /}
   */
  @SuppressWarnings("unchecked")
  public static PresenceResolver of(
      RouteTemplate template,
      Object rawVars,
      String rawRemainder,
      boolean lookupPathEndsWithSlash) {
    String remainder =
        rawRemainder != null && rawRemainder.isEmpty() && lookupPathEndsWithSlash
            ? "/"
            : rawRemainder;
    return of(template, rawVars instanceof Map ? (Map<String, ?>) rawVars : null, remainder);
  }

  @Override
  public RoutePresence presenceOf(RoutePart.Param param) {
    String name = param.frameworkName();
    if (name == null) {
      return param == remainderCatchAll
          ? RoutePresence.ofCatchAllValue(unnamedCatchAllRemainder)
          : RoutePresence.UNKNOWN;
    }
    if (boundValues == null) {
      return RoutePresence.UNKNOWN;
    }
    String value = valueOf(boundValues.get(name));
    return param.isCatchAll()
        ? RoutePresence.ofCatchAllValue(value)
        : RoutePresence.ofBoundValue(value);
  }

  @Override
  public RoutePresence presenceOf(RoutePart.Group group) {
    return RoutePresence.UNKNOWN;
  }

  private static String valueOf(Object value) {
    if (value instanceof List) {
      List<?> list = (List<?>) value;
      value = list.isEmpty() ? null : list.get(0);
    }
    return value == null ? null : String.valueOf(value);
  }
}
