package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.RoutePart.catchAll;
import static datadog.trace.api.appsec.route.RoutePart.group;
import static datadog.trace.api.appsec.route.RoutePart.literal;
import static datadog.trace.api.appsec.route.RoutePart.optionalParam;
import static datadog.trace.api.appsec.route.RoutePart.param;
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
    assertEquals(RoutePresence.PRESENT, resolver.presenceOf(catchAll(null)));
    assertEquals(
        RoutePresence.ABSENT_TRAILING_SLASH, resolver(null, "/").presenceOf(catchAll(null)));
    assertEquals(RoutePresence.ABSENT, resolver(null, null).presenceOf(catchAll(null)));
  }

  @Test
  void namedParamPresenceComesFromTheTokens() {
    assertEquals(
        RoutePresence.PRESENT,
        resolver(singletonMap("x", "1"), null).presenceOf(optionalParam("x")));
    assertEquals(
        RoutePresence.ABSENT, resolver(singletonMap("x", ""), null).presenceOf(optionalParam("x")));
    assertEquals(
        RoutePresence.ABSENT,
        resolver(singletonMap("y", "1"), null).presenceOf(optionalParam("x")));
    assertEquals(RoutePresence.UNKNOWN, resolver(null, "a").presenceOf(optionalParam("x")));
  }

  @Test
  void unnamedParamsAndGroupsAreUnknown() {
    PresenceResolver resolver = resolver(singletonMap("x", "1"), "a");
    assertEquals(RoutePresence.UNKNOWN, resolver.presenceOf(param(null)));
    assertEquals(RoutePresence.UNKNOWN, resolver.presenceOf(group(literal("a"))));
  }

  private static PresenceResolver resolver(Map<String, ?> tokens, String remainder) {
    return RatpackPresenceResolver.of(
        RatpackRouteParser.parse("a/:x?", false, true), tokens, remainder);
  }
}
