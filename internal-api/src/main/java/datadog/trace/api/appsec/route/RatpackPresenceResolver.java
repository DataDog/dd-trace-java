package datadog.trace.api.appsec.route;

import java.util.Map;

/**
 * {@link PresenceResolver} for a template built by {@link RatpackRouteParser}. Named parameters use
 * {@code PathBinding.getAllTokens()} (a null map is {@link RoutePresence#UNKNOWN}, a missing key or
 * empty value is absent). The unnamed catch-all appended for a {@code prefix(x){all{}}} remainder,
 * the only catch-all a Ratpack template has, takes its presence straight from {@code
 * PathBinding.getPastBinding()}, which is exactly the unconsumed rest of the path, whatever
 * parameters come before it.
 */
public final class RatpackPresenceResolver implements PresenceResolver {

  private final Map<String, ?> tokens;
  private final String remainder;

  private RatpackPresenceResolver(Map<String, ?> tokens, String remainder) {
    this.tokens = tokens;
    this.remainder = remainder;
  }

  /**
   * @param template the template being rendered
   * @param tokens {@code PathBinding.getAllTokens()}, or null when not exposed
   * @param remainder {@code PathBinding.getPastBinding()}; null or empty means no remainder
   */
  public static PresenceResolver of(
      RouteTemplate template, Map<String, ?> tokens, String remainder) {
    if (template == null || !template.isExpressible() || template.staticRoute() != null) {
      // a pre-rendered or non-expressible template never asks for presence
      return PresenceResolver.UNKNOWN;
    }
    return new RatpackPresenceResolver(tokens, remainder);
  }

  @Override
  public RoutePresence presenceOf(RoutePart.Param param) {
    String name = param.frameworkName();
    if (name == null) {
      return param.isCatchAll() ? RoutePresence.ofCatchAllValue(remainder) : RoutePresence.UNKNOWN;
    }
    if (tokens == null) {
      return RoutePresence.UNKNOWN;
    }
    Object value = tokens.get(name);
    return RoutePresence.ofBoundValue(value == null ? null : String.valueOf(value));
  }

  @Override
  public RoutePresence presenceOf(RoutePart.Group group) {
    return RoutePresence.UNKNOWN;
  }
}
