package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.RoutePart.UNRESOLVED;
import static datadog.trace.api.appsec.route.RoutePart.catchAll;
import static datadog.trace.api.appsec.route.RoutePart.group;
import static datadog.trace.api.appsec.route.RoutePart.literal;
import static datadog.trace.api.appsec.route.RoutePart.optionalParam;
import static datadog.trace.api.appsec.route.RoutePart.param;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class RoutePartTest {

  @TableTest({
    "Scenario  | Text   | Expected text",
    "plain     | 'abc'  | 'abc'        ",
    "non-ASCII | 'café' | 'café'       ",
    "empty     | ''     | ''           ",
    "null      |        | ''           "
  })
  void literalKeepsDeclaredTextUntilResolved(String text, String expectedText) {
    RoutePart.Literal literal = literal(text);

    assertEquals(expectedText, literal.text());
    assertNull(literal.encoded());
    assertEquals(expectedText, literal.toString());
  }

  @Test
  void resolvedLiteralRendersEncodedText() {
    RoutePart.Literal literal = new RoutePart.Literal("café", "caf%C3%A9");

    assertEquals("café", literal.text());
    assertEquals("caf%C3%A9", literal.encoded());
    assertEquals("caf%C3%A9", literal.toString());
  }

  @TableTest({
    "Scenario          | Factory  | Name | Framework name | Optional | Catch-all | Expected ",
    "named             | param    | id   | id             | false    | false     | '{id}'   ",
    "unnamed null      | param    |      |                | false    | false     | '{null}' ",
    "unnamed empty     | param    | ''   |                | false    | false     | '{null}' ",
    "optional          | optional | tab  | tab            | true     | false     | '{tab?}' ",
    "catch-all         | catchAll | path | path           | true     | true      | '{path?}'",
    "unnamed catch-all | catchAll |      |                | true     | true      | '{null?}'"
  })
  void paramFactoriesDescribeTheDeclaration(
      String factory,
      String name,
      String frameworkName,
      boolean optional,
      boolean catchAll,
      String expected) {
    RoutePart.Param param = create(factory, name);

    assertEquals(frameworkName, param.frameworkName());
    assertEquals(optional, param.isOptional());
    assertEquals(catchAll, param.isCatchAll());
    assertEquals(UNRESOLVED, param.index());
    assertNull(param.name());
    assertEquals(expected, param.toString());
  }

  @TableTest({
    "Scenario          | Route          | Index | Framework name | Name    | Expected   ",
    "named             | '/a/{id}'      | 0     | id             | id      | '{id}'     ",
    "sanitized         | '/a/{user-id}' | 0     | user-id        | user_id | '{user_id}'",
    "unnamed           | '/a/*'         | 0     |                | param1  | '{param1}' ",
    "optional          | '/a/{id}{x?}'  | 1     | x              | x       | '{x?}'     ",
    "unnamed catch-all | '/a/{id}/**'   | 1     |                | param1  | '{param1?}'",
    "named catch-all   | '/a/{*path}'   | 0     | path           | path    | '{path?}'  "
  })
  void resolvedParamsUseRenderedNames(
      String route, int index, String frameworkName, String name, String expected) {
    RoutePart.Param param = RfcRouteNotation.parse(route).params().get(index);

    assertEquals(index, param.index());
    assertEquals(frameworkName, param.frameworkName());
    assertEquals(name, param.name());
    assertEquals(expected, param.toString());
  }

  @Test
  void inlineGroupKeepsItsParts() {
    RoutePart.Literal dot = literal(".");
    RoutePart.Param format = param("format");
    RoutePart.Group group = group(dot, format);

    assertEquals(asList(dot, format), group.parts());
    assertFalse(group.isWholeSegment());
    assertEquals(UNRESOLVED, group.index());
    assertEquals("(.{format})", group.toString());
  }

  @Test
  void wholeSegmentGroupIncludesTheSeparator() {
    RoutePart.Group group =
        new RoutePart.Group(asList(literal("page-"), param("n")), true, UNRESOLVED);

    assertTrue(group.isWholeSegment());
    assertEquals("(/page-{n})", group.toString());
  }

  @Test
  void emptyGroupRendersBrackets() {
    assertEquals("()", group().toString());
    assertTrue(group().parts().isEmpty());
  }

  @Test
  void groupPartsAreUnmodifiable() {
    List<RoutePart> parts = new ArrayList<>();
    parts.add(literal("x"));
    RoutePart.Group group = new RoutePart.Group(parts, false, 3);

    assertEquals(3, group.index());
    assertThrows(UnsupportedOperationException.class, () -> group.parts().add(literal("y")));
  }

  @Test
  void resolvedGroupsAreIndexedInPathOrder() {
    RouteTemplate template =
        RouteTemplate.builder()
            .segment(literal("a"), group(literal("."), param("x")))
            .optionalSegment(literal("b"))
            .build();
    final List<RoutePart.Group> groups = new ArrayList<>();
    template.render(
        null,
        new PresenceResolver() {
          @Override
          public RoutePresence presenceOf(RoutePart.Param param) {
            return RoutePresence.PRESENT;
          }

          @Override
          public RoutePresence presenceOf(RoutePart.Group group) {
            groups.add(group);
            return RoutePresence.PRESENT;
          }
        });

    assertEquals(2, groups.size());
    assertEquals(0, groups.get(0).index());
    assertFalse(groups.get(0).isWholeSegment());
    assertEquals("(.{x})", groups.get(0).toString());
    assertEquals(1, groups.get(1).index());
    assertTrue(groups.get(1).isWholeSegment());
    assertEquals("(/b)", groups.get(1).toString());
  }

  @TableTest({
    "Scenario                | Route                    | Expected                ",
    "root                    | '/'                      | '/'                     ",
    "static                  | '/users'                 | '/users'                ",
    "encoded literal         | '/café'                  | '/caf%C3%A9'            ",
    "params                  | '/users/{id}.{format}'   | '/users/{id}.{format}'  ",
    "sanitized name          | '/a/{user-id}'           | '/a/{user_id}'          ",
    "trailing slash          | '/users/{id}/'           | '/users/{id}/'          ",
    "optional param in group | '/search.{format?}'      | '/search(.{format})'    ",
    "optional segment        | '/a/{x?}'                | '/a(/{x})'              ",
    "optional static segment | '/drafts[/preview]'      | '/drafts(/preview)'     ",
    "inline group            | '/posts/{id}(.{format})' | '/posts/{id}(.{format})'",
    "named catch-all         | '/files/{*path}'         | '/files/{path?}'        ",
    "unnamed catch-all       | '/static/**'             | '/static/{param1?}'     "
  })
  void templateToStringShowsResolvedParts(String route, String expected) {
    assertEquals(expected, RfcRouteNotation.parse(route).toString());
  }

  @Test
  void notExpressibleToString() {
    assertEquals("RouteTemplate[not expressible]", RouteTemplate.NOT_EXPRESSIBLE.toString());
  }

  private static RoutePart.Param create(String factory, String name) {
    if ("param".equals(factory)) {
      return param(name);
    }
    if ("optional".equals(factory)) {
      return optionalParam(name);
    }
    return catchAll(name);
  }
}
