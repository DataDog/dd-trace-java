package datadog.trace.api.appsec.route;

import java.util.List;
import java.util.Map;

/**
 * {@link PresenceResolver} backed by the path variables the framework bound for the current request
 * (e.g. Spring {@code URI_TEMPLATE_VARIABLES_ATTRIBUTE}), since RFC-1125 presence must come from
 * the framework's own binding. A null map is {@link RoutePresence#UNKNOWN} (not exposed), while a
 * missing key or null value is absent. A named catch-all {@code {*name}} is looked up under {@code
 * name} (PathPattern) and then {@code *name} (legacy AntPathMatcher, which binds it as a
 * single-segment variable with that literal name). Unnamed parts and groups have no binding, so
 * they are unknown, except a trailing {@code **} that takes the matcher remainder (see {@link
 * RouteTemplate#needsUnnamedCatchAllRemainder()}).
 */
public final class BoundValuesPresenceResolver implements PresenceResolver {

  /** Key prefix under which {@code AntPathMatcher} binds a {@code {*name}} declaration. */
  private static final String ANT_CATCH_ALL_PREFIX = "*";

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
  public static PresenceResolver fromRaw(
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
    if (!param.isCatchAll()) {
      return RoutePresence.ofBoundValue(value);
    }
    if (value == null) {
      // legacy AntPathMatcher has no catch-all variable: it binds {*name} as a single-segment
      // variable named "*name", which is still a value the framework bound from the URL
      value = valueOf(boundValues.get(ANT_CATCH_ALL_PREFIX + name));
    }
    return RoutePresence.ofCatchAllValue(value);
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
