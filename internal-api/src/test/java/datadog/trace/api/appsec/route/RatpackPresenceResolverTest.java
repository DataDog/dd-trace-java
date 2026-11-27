package datadog.trace.api.appsec.route;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RatpackPresenceResolverTest {

  @Test
  void templatesThatNeverAskForPresenceGetTheSharedUnknownResolver() {
    assertSame(PresenceResolver.UNKNOWN, RatpackPresenceResolver.of(null, null, "a"));
    assertSame(
        PresenceResolver.UNKNOWN,
        RatpackPresenceResolver.of(RouteTemplate.NOT_EXPRESSIBLE, null, "a"));
    assertSame(
        PresenceResolver.UNKNOWN,
        RatpackPresenceResolver.of(RatpackRouteParser.parse("a/:id", false, false), null, null));
  }

  @Test
  void dynamicTemplateGetsItsOwnResolver() {
    assertNotSame(
        PresenceResolver.UNKNOWN,
        RatpackPresenceResolver.of(RatpackRouteParser.parse("a/:x?", false, false), null, null));
  }

  @Test
  void catchAllPresenceComesFromTheRemainder() {
    PresenceResolver resolver = resolver(null, "a");
    assertEquals(RoutePresence.PRESENT, resolver.presenceOf(RoutePart.catchAll(null)));
    assertEquals(
        RoutePresence.ABSENT_TRAILING_SLASH,
        resolver(null, "/").presenceOf(RoutePart.catchAll(null)));
    assertEquals(RoutePresence.ABSENT, resolver(null, null).presenceOf(RoutePart.catchAll(null)));
  }

  @Test
  void namedParamPresenceComesFromTheTokens() {
    assertEquals(
        RoutePresence.PRESENT,
        resolver(singletonMap("x", "1"), null).presenceOf(RoutePart.optionalParam("x")));
    assertEquals(
        RoutePresence.ABSENT,
        resolver(singletonMap("x", ""), null).presenceOf(RoutePart.optionalParam("x")));
    assertEquals(
        RoutePresence.ABSENT,
        resolver(singletonMap("y", "1"), null).presenceOf(RoutePart.optionalParam("x")));
    assertEquals(
        RoutePresence.UNKNOWN, resolver(null, "a").presenceOf(RoutePart.optionalParam("x")));
  }

  @Test
  void unnamedParamsAndGroupsAreUnknown() {
    PresenceResolver resolver = resolver(singletonMap("x", "1"), "a");
    assertEquals(RoutePresence.UNKNOWN, resolver.presenceOf(RoutePart.param(null)));
    assertEquals(
        RoutePresence.UNKNOWN, resolver.presenceOf(RoutePart.group(RoutePart.literal("a"))));
  }

  private static PresenceResolver resolver(Map<String, ?> tokens, String remainder) {
    return RatpackPresenceResolver.of(
        RatpackRouteParser.parse("a/:x?", false, true), tokens, remainder);
  }
}
