package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.RoutePart.catchAll;
import static datadog.trace.api.appsec.route.RoutePart.literal;
import static datadog.trace.api.appsec.route.RoutePart.optionalParam;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class BoundValuesPresenceResolverTest {

  private static final RouteTemplate OPTIONAL_ID =
      RouteTemplate.builder().segment(literal("p")).segment(optionalParam("id")).build();

  @TableTest({
    "Scenario                       | Route             | Name | Value  | Expected        ",
    "named catch-all with value     | '/files/{*path}'  | path | '/a/b' | '/files/{path}' ",
    "named catch-all only slash     | '/files/{*path}'  | path | '/'    | '/files/'       ",
    "named catch-all empty          | '/files/{*path}'  | path | ''     | '/files'        ",
    "named catch-all missing key    | '/files/{*path}'  | x    | '/a/b' | '/files'        ",
    "named catch-all null value     | '/files/{*path}'  | path |        | '/files'        ",
    "required params unaffected     | '/u/{id}/{*rest}' | id   | '1'    | '/u/{id}'       ",
    "catch-all slash after param    | '/u/{id}/{*rest}' | rest | '/'    | '/u/{id}/'      ",
    "required params with catch-all | '/u/{id}/{*rest}' | rest | '/x'   | '/u/{id}/{rest}'"
  })
  void namedValues(String route, String name, String value, String expected) {
    Map<String, Object> vars = new HashMap<>();
    vars.put(name, value);
    RouteTemplate template = SpringRouteParser.parse(route);

    assertEquals(expected, render(template, vars, null));
  }

  @Test
  void nullMapFallsBack() {
    RouteTemplate template = SpringRouteParser.parse("/files/{*path}");

    PresenceResolver resolver = BoundValuesPresenceResolver.of(template, null, null);

    assertSame(PresenceResolver.UNKNOWN, resolver);
    assertEquals("/files", template.render(null, resolver));
  }

  @TableTest({
    "Scenario    | Name  | Value | Expected ",
    "present     | id    | '7'   | '/p/{id}'",
    "empty       | id    | ''    | '/p'     ",
    "missing key | other | '7'   | '/p'     ",
    "null value  | id    |       | '/p'     "
  })
  void namedOptionalParam(String name, String value, String expected) {
    assertEquals(expected, render(OPTIONAL_ID, singletonMap(name, value), null));
  }

  @Test
  void namedOptionalParamNonStringValues() {
    assertEquals("/p/{id}", render(OPTIONAL_ID, singletonMap("id", 7), null));
    assertEquals("/p", render(OPTIONAL_ID, Collections.<String, Object>emptyMap(), null));
    assertEquals("/p", render(OPTIONAL_ID, null, null));
  }

  @Test
  void listValuesUseTheFirstElement() {
    RouteTemplate template = SpringRouteParser.parse("/files/{*path}");

    assertEquals("/files/{path}", render(template, singletonMap("path", asList("/a", "")), null));
    assertEquals("/files/", render(template, singletonMap("path", asList("/", "/a")), null));
    assertEquals("/files", render(template, singletonMap("path", emptyList()), null));
  }

  @TableTest({
    "Scenario                      | Route        | Remainder | Expected          ",
    "trailing ** with remainder    | '/files/**'  | 'a/b'     | '/files/{param1}' ",
    "trailing ** empty remainder   | '/files/**'  | ''        | '/files'          ",
    "trailing ** slash remainder   | '/files/**'  | '/'       | '/files/'         ",
    "trailing ** null remainder    | '/files/**'  |           | '/files'          ",
    "mid-path ** ignores remainder | '/a/**/b'    | 'x'       | '/a/b'            ",
    "two ** ignore remainder       | '/a/**/b/**' | 'x'       | '/a/b'            ",
    "trailing slash ignores it     | '/files/**/' | 'x'       | '/files/'         ",
    "wildcard before ignores it    | '/a/*/**'    | 'x/y'     | '/a/{param1}'     ",
    "named param before uses it    | '/a/{id}/**' | 'x/y'     | '/a/{id}/{param1}'"
  })
  void unnamedCatchAllRemainder(String route, String remainder, String expected) {
    assertEquals(expected, render(SpringRouteParser.parse(route), null, remainder));
  }

  @Test
  void remainderDoesNotAffectNamedCatchAll() {
    RouteTemplate template =
        RouteTemplate.builder().segment(literal("f")).segment(catchAll("path")).build();

    assertEquals("/f", render(template, null, "a/b"));
  }

  @Test
  void groupsAreUnknown() {
    RouteTemplate template =
        RouteTemplate.builder()
            .segment(literal("posts"), RoutePart.group(literal("."), RoutePart.param("format")))
            .build();

    assertEquals("/posts", render(template, singletonMap("format", "json"), null));
  }

  @Test
  void staticTemplateDoesNotReadTheMap() {
    Map<String, Object> unreadable =
        new AbstractMap<String, Object>() {
          @Override
          public Set<Entry<String, Object>> entrySet() {
            throw new AssertionError("map read");
          }

          @Override
          public Object get(Object key) {
            throw new AssertionError("map read");
          }
        };
    RouteTemplate template = SpringRouteParser.parse("/users/{id}");

    assertSame(PresenceResolver.UNKNOWN, BoundValuesPresenceResolver.of(template, unreadable, "x"));
    assertSame(
        PresenceResolver.UNKNOWN,
        BoundValuesPresenceResolver.of(template, (Object) unreadable, "x", true));
  }

  @Test
  void nullTemplateIsUnknown() {
    assertSame(
        PresenceResolver.UNKNOWN,
        BoundValuesPresenceResolver.of(null, singletonMap("id", "1"), "x"));
  }

  @TableTest({
    "Scenario                         | Remainder | Lookup ends with slash | Expected         ",
    "remainder used as is             | 'a/b'     | false                  | '/files/{param1}'",
    "remainder used as is, slash path | 'a/'      | true                   | '/files/{param1}'",
    "empty remainder, no slash        | ''        | false                  | '/files'         ",
    "empty remainder, slash path      | ''        | true                   | '/files/'        ",
    "not computed                     |           | true                   | '/files'         "
  })
  void rawRemainderAppliesTheTrailingSlashRule(
      String remainder, boolean endsWithSlash, String expected) {
    RouteTemplate template = SpringRouteParser.parse("/files/**");

    assertEquals(
        expected,
        template.render(
            null, BoundValuesPresenceResolver.of(template, null, remainder, endsWithSlash)));
  }

  @Test
  void rawVarsAreUsedOnlyWhenMap() {
    RouteTemplate template = SpringRouteParser.parse("/files/{*path}");
    Object vars = singletonMap("path", "/a");

    assertEquals(
        "/files/{path}",
        template.render(null, BoundValuesPresenceResolver.of(template, vars, null, false)));
    assertSame(
        PresenceResolver.UNKNOWN,
        BoundValuesPresenceResolver.of(template, "not a map", null, false));
  }

  private static String render(RouteTemplate template, Map<String, ?> vars, String remainder) {
    return template.render(null, BoundValuesPresenceResolver.of(template, vars, remainder));
  }
}
