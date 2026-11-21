package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.RoutePart.catchAll;
import static datadog.trace.api.appsec.route.RoutePart.group;
import static datadog.trace.api.appsec.route.RoutePart.literal;
import static datadog.trace.api.appsec.route.RoutePart.optionalParam;
import static datadog.trace.api.appsec.route.RoutePart.param;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class RouteTemplateTest {

  private static final PresenceResolver FAILING =
      new PresenceResolver() {
        @Override
        public RoutePresence presenceOf(RoutePart.Param param) {
          throw new AssertionError("unexpected presence query for " + param);
        }

        @Override
        public RoutePresence presenceOf(RoutePart.Group group) {
          throw new AssertionError("unexpected presence query for " + group);
        }
      };

  @TableTest({
    "Scenario                                 | Route                    | Present    | Expected     ",
    "catch-all unknown                        | '/files/{*path}'         |            | '/files'     ",
    "catch-all absent                         | '/files/{*path}'         | {}         | '/files'     ",
    "catch-all matched only slash             | '/files/{*path}'         | {'path=/'} | '/files/'    ",
    "catch-all only slash, declared slash     | '/files/{*path}/'        | {'path=/'} | '/files/'    ",
    "root catch-all absent                    | '/{*path}'               | {}         | '/'          ",
    "mid-path catch-all absent                | '/a/**/b'                | {}         | '/a/b'       ",
    "mid-path catch-all only slash            | '/a/**/b'                | {'anon=/'} | '/a/b'       ",
    "group unknown                            | '/posts/{id}(.{format})' |            | '/posts/{id}'",
    "declared trailing slash after absent     | '/a/{x?}/'               | {}         | '/a/'        ",
    "declared trailing slash after present    | '/a/{x?}/'               | {x}        | '/a/{x}/'    ",
    "everything absent                        | '/{x?}'                  | {}         | '/'          ",
    "optional param without separator present | '/v/{a}{b?}'             | {b}        | '/v/{a}{b}'  ",
    "optional param without separator absent  | '/v/{a}{b?}'             | {}         | '/v/{a}'     "
  })
  void rendersOptionalPartsAndCatchAlls(String route, Set<String> present, String expected) {
    RouteTemplate template = RfcRouteNotation.parse(route);
    assertEquals(expected, template.render(null, RfcRouteNotation.presence(present)));
  }

  @TableTest({
    "Scenario                         | Route             | Expected                              ",
    "replaced names collide together  | '/a/{a-b}/{a.b}'  | '/a/{a_b}/{a_b_2}'                    ",
    "surrogate pair is one code point | '/a/{x😀}'        | '/a/{x_}'                             ",
    "unnamed skips used names         | '/{param2}/*/*/*' | '/{param2}/{param1}/{param3}/{param4}'",
    "unnamed after replaced name      | '/{param-1}/*'    | '/{param_1}/{param1}'                 "
  })
  void resolvesParameterNames(String route, String expected) {
    assertEquals(expected, RfcRouteNotation.parse(route).render(null, null));
  }

  @TableTest({
    "Scenario               | Prefix        | Route         | Expected             ",
    "no prefix              |               | '/users/{id}' | '/users/{id}'        ",
    "empty prefix           | ''            | '/users/{id}' | '/users/{id}'        ",
    "context path           | '/ctx'        | '/users/{id}' | '/ctx/users/{id}'    ",
    "prefix trailing slash  | '/ctx/'       | '/users/{id}' | '/ctx/users/{id}'    ",
    "prefix without slash   | 'ctx'         | '/users/{id}' | '/ctx/users/{id}'    ",
    "prefix empty segments  | '//ctx//api/' | '/users/{id}' | '/ctx/api/users/{id}'",
    "slash prefix           | '/'           | '/users'      | '/users'             ",
    "prefix and root route  | '/ctx'        | '/'           | '/ctx/'              ",
    "prefix encoded         | '/café'       | '/users'      | '/caf%C3%A9/users'   ",
    "prefix percent         | '/a%20b'      | '/users'      | '/a%2520b/users'     ",
    "prefix and optional    | '/ctx'        | '/a/{x?}'     | '/ctx/a'             ",
    "prefix, all absent     | '/ctx'        | '/{*path}'    | '/ctx'               ",
    "prefix, optional seg   | '/ctx'        | '/{x?}'       | '/ctx'               ",
    "prefix, declared slash | '/ctx'        | '/{x?}/'      | '/ctx/'              ",
    "no prefix, all absent  |               | '/{*path}'    | '/'                  "
  })
  void concatenatesPrefix(String prefix, String route, String expected) {
    assertEquals(expected, RfcRouteNotation.parse(route).render(prefix, null));
  }

  @TableTest({
    "Scenario                 | Prefix | Route      | Present    | Expected     ",
    "only slash keeps slash   | '/ctx' | '/{*path}' | {'path=/'} | '/ctx/'      ",
    "present                  | '/ctx' | '/{*path}' | {path}     | '/ctx/{path}'",
    "absent                   | '/ctx' | '/{*path}' | {}         | '/ctx'       ",
    "unnamed root catch-all   | '/ctx' | '/**'      | {}         | '/ctx'       ",
    "unnamed root, only slash | '/ctx' | '/**'      | {'anon=/'} | '/ctx/'      "
  })
  void absentRouteBodyRendersThePrefixAlone(
      String prefix, String route, Set<String> present, String expected) {
    RouteTemplate template = RfcRouteNotation.parse(route);
    assertEquals(expected, template.render(prefix, RfcRouteNotation.presence(present)));
  }

  @Test
  void rootRouteKeepsItsSlashUnderAPrefix() {
    assertEquals("/ctx/", RouteTemplate.builder().trailingSlash(true).build().render("/ctx", null));
    assertEquals("/", RouteTemplate.builder().build().render(null, null));
  }

  @Test
  void staticRouteIsRenderedOnceAndReused() {
    RouteTemplate template =
        RouteTemplate.builder()
            .segment(literal("photos"))
            .segment(param("id"), literal("."), param("format"))
            .build();

    String route = template.staticRoute();
    assertEquals("/photos/{id}.{format}", route);
    assertSame(route, template.render(null, FAILING));
    assertSame(route, template.render("", null));
    assertEquals("/ctx/photos/{id}.{format}", template.render("/ctx", FAILING));
  }

  @Test
  void dynamicRouteHasNoStaticValue() {
    assertNull(RfcRouteNotation.parse("/users/{id}/{tab?}").staticRoute());
    assertNull(RfcRouteNotation.parse("/files/{*path}").staticRoute());
    assertNull(RfcRouteNotation.parse("/items(.{format})").staticRoute());
  }

  @Test
  void notExpressibleRendersNoTag() {
    assertFalse(RouteTemplate.NOT_EXPRESSIBLE.isExpressible());
    assertNull(RouteTemplate.NOT_EXPRESSIBLE.render("/ctx", null));
    assertNull(RouteTemplate.NOT_EXPRESSIBLE.staticRoute());
    assertTrue(RfcRouteNotation.parse("/").isExpressible());
    assertNotNull(RfcRouteNotation.parse("/").render(null, null));
  }

  @Test
  void fallbackRemovesOnlyUnknownParts() {
    RouteTemplate template =
        RouteTemplate.builder()
            .segment(literal("a"))
            .optionalSegment(param("x"))
            .segment(param("id"), optionalParam("y"), group(literal("."), param("format")))
            .build();
    PresenceResolver resolver =
        new PresenceResolver() {
          @Override
          public RoutePresence presenceOf(RoutePart.Param param) {
            return "y".equals(param.frameworkName())
                ? RoutePresence.UNKNOWN
                : RoutePresence.PRESENT;
          }

          @Override
          public RoutePresence presenceOf(RoutePart.Group group) {
            return group.isWholeSegment() ? RoutePresence.PRESENT : RoutePresence.UNKNOWN;
          }
        };

    assertEquals("/a/{x}/{id}", template.render(null, resolver));
  }

  @Test
  void partsInsideAbsentGroupAreNotQueried() {
    RouteTemplate template =
        RouteTemplate.builder()
            .segment(literal("a"))
            .optionalSegment(literal("b-"), optionalParam("x"))
            .build();
    final List<String> queried = new ArrayList<>();
    PresenceResolver resolver =
        new PresenceResolver() {
          @Override
          public RoutePresence presenceOf(RoutePart.Param param) {
            queried.add(param.name());
            return RoutePresence.PRESENT;
          }

          @Override
          public RoutePresence presenceOf(RoutePart.Group group) {
            queried.add("group" + group.index());
            return RoutePresence.ABSENT;
          }
        };

    assertEquals("/a", template.render(null, resolver));
    assertEquals(1, queried.size());
    assertEquals("group0", queried.get(0));
  }

  @Test
  void builderIgnoresNullPartsAndNullElements() {
    RouteTemplate template =
        RouteTemplate.builder()
            .segment((RoutePart[]) null)
            .segment(literal("a"), null)
            .segment((RoutePart) null)
            .optionalSegment((RoutePart[]) null)
            .optionalSegment((RoutePart) null)
            .segment(null, param("id"))
            .build();

    assertEquals("/a/{id}", template.render(null, null));
  }

  @Test
  void resolvedParamsExposeNamesAndPathOrder() {
    RouteTemplate template =
        RouteTemplate.builder()
            .segment(literal("w"))
            .segment(param("param1"))
            .segment(catchAll(null))
            .build();

    List<RoutePart.Param> params = template.params();
    assertEquals(2, params.size());
    assertEquals(0, params.get(0).index());
    assertEquals("param1", params.get(0).name());
    assertEquals(1, params.get(1).index());
    assertNull(params.get(1).frameworkName());
    assertEquals("param2", params.get(1).name());
    assertTrue(params.get(1).isCatchAll());
    assertTrue(params.get(1).isOptional());
  }

  @Test
  void slashOnlyCatchAllLosesItsSlashWhenALaterOptionalSegmentIsSkipped() {
    RouteTemplate template =
        RouteTemplate.builder()
            .segment(literal("files"))
            .segment(catchAll("path"))
            .optionalSegment(param("x"))
            .build();
    PresenceResolver resolver =
        new PresenceResolver() {
          @Override
          public RoutePresence presenceOf(RoutePart.Param param) {
            return RoutePresence.ABSENT_TRAILING_SLASH;
          }

          @Override
          public RoutePresence presenceOf(RoutePart.Group group) {
            return RoutePresence.ABSENT;
          }
        };

    assertEquals("/files", template.render(null, resolver));
  }
}
