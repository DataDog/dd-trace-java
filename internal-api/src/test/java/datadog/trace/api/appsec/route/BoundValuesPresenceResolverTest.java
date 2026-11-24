package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.RoutePart.catchAll;
import static datadog.trace.api.appsec.route.RoutePart.group;
import static datadog.trace.api.appsec.route.RoutePart.literal;
import static datadog.trace.api.appsec.route.RoutePart.optionalParam;
import static datadog.trace.api.appsec.route.RoutePart.param;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.AbstractMap;
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
    assertEquals("/p", render(OPTIONAL_ID, emptyMap(), null));
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
    "trailing slash with remainder | '/files/**/' | 'css/a'   | '/files/{param1}/'",
    "trailing slash empty          | '/files/**/' | ''        | '/files/'         ",
    "trailing slash only slash     | '/files/**/' | '/'       | '/files/'         ",
    "trailing slash null remainder | '/files/**/' |           | '/files/'         ",
    "wildcard before ignores it    | '/a/*/**'    | 'x/y'     | '/a/{param1}'     ",
    "named param before uses it    | '/a/{id}/**' | 'x/y'     | '/a/{id}/{param1}'"
  })
  void unnamedCatchAllRemainder(String route, String remainder, String expected) {
    assertEquals(expected, render(SpringRouteParser.parse(route), null, remainder));
  }

  @TableTest({
    "Scenario                  | Route                 | Remainder | Expected        ",
    "optional-like name before | '/files/{file?}/**'   | 'report'  | '/files/{file_}'",
    "name with * before        | '/files/{f*}/**'      | 'report'  | '/files/{f_}'   ",
    "regex param before        | '/files/{id:\\d+}/**' | '1/x'     | '/files/{id}'   ",
    "named catch-all before    | '/a/{*p}/**'          | 'x/y'     | '/a'            ",
    "trailing slash, * before  | '/a/*/**/'            | 'x/y'     | '/a/{param1}/'  "
  })
  void remainderIgnoredWhenTheMatcherStartsEarlier(
      String route, String remainder, String expected) {
    assertEquals(expected, render(SpringRouteParser.parse(route), null, remainder));
  }

  @TableTest({
    "Scenario                       | Key     | Value    | Expected       ",
    "PathPattern binding            | path    | '/a/b'   | '/files/{path}'",
    "PathPattern binding only slash | path    | '/'      | '/files/'      ",
    "PathPattern binding empty      | path    | ''       | '/files'       ",
    "Ant binding single segment     | '*path' | 'report' | '/files/{path}'",
    "Ant binding empty              | '*path' | ''       | '/files'       ",
    "Ant binding null               | '*path' |          | '/files'       ",
    "neither binding                | other   | 'report' | '/files'       "
  })
  void namedCatchAllPathPatternAndAntBindings(String key, String value, String expected) {
    Map<String, Object> vars = new HashMap<>();
    vars.put(key, value);

    assertEquals(expected, render(SpringRouteParser.parse("/files/{*path}"), vars, null));
  }

  @Test
  void pathPatternBindingWinsOverAntBinding() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("path", "/a/b");
    vars.put("*path", "");

    assertEquals("/files/{path}", render(SpringRouteParser.parse("/files/{*path}"), vars, null));
  }

  @Test
  void antBindingIsOnlyForCatchAlls() {
    assertEquals("/p", render(OPTIONAL_ID, singletonMap("*id", "7"), null));
  }

  @Test
  void midPathUnnamedCatchAllFallsBack() {
    RouteTemplate template = SpringRouteParser.parse("/a/**/b");

    assertEquals("/a/b", render(template, singletonMap("x", "1"), "x/y"));
    assertEquals("/a/b", render(template, emptyMap(), null));
  }

  @Test
  void duplicateValidNamesAreKept() {
    // RFC-1125 renames only names that had invalid characters replaced
    RouteTemplate template = SpringRouteParser.parse("/a/{id}/b/{id}");

    assertEquals("/a/{id}/b/{id}", render(template, singletonMap("id", "1"), null));
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
            .segment(literal("posts"), group(literal("."), param("format")))
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
        BoundValuesPresenceResolver.fromRaw(template, unreadable, "x", true));
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
            null, BoundValuesPresenceResolver.fromRaw(template, null, remainder, endsWithSlash)));
  }

  @TableTest({
    "Scenario                    | Remainder   | Lookup ends with slash | Expected          ",
    "remainder, slash path       | 'css/a.css' | true                   | '/files/{param1}/'",
    "empty remainder, slash path | ''          | true                   | '/files/'         ",
    "empty remainder, no slash   | ''          | false                  | '/files/'         "
  })
  void rawRemainderWithDeclaredTrailingSlash(
      String remainder, boolean endsWithSlash, String expected) {
    RouteTemplate template = SpringRouteParser.parse("/files/**/");

    assertEquals(
        expected,
        template.render(
            null, BoundValuesPresenceResolver.fromRaw(template, null, remainder, endsWithSlash)));
  }

  @Test
  void rawVarsAreUsedOnlyWhenMap() {
    RouteTemplate template = SpringRouteParser.parse("/files/{*path}");
    Object vars = singletonMap("path", "/a");

    assertEquals(
        "/files/{path}",
        template.render(null, BoundValuesPresenceResolver.fromRaw(template, vars, null, false)));
    assertSame(
        PresenceResolver.UNKNOWN,
        BoundValuesPresenceResolver.fromRaw(template, "not a map", null, false));
  }

  private static String render(RouteTemplate template, Map<String, ?> vars, String remainder) {
    return template.render(null, BoundValuesPresenceResolver.of(template, vars, remainder));
  }
}
