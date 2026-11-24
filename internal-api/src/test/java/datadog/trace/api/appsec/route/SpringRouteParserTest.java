package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class SpringRouteParserTest {

  @TableTest({
    "Scenario                         | Pattern                 | Present    | Expected              ",
    "QA regex stripped                | '/path/{id:\\d+}/param' | {}         | '/path/{id}/param'    ",
    "catch-all unknown                | '/files/{*path}'        |            | '/files'              ",
    "catch-all matched only slash     | '/files/{*path}'        | {'path=/'} | '/files/'             ",
    "unnamed catch-all absent         | '/static/**'            | {}         | '/static'             ",
    "unnamed catch-all only slash     | '/static/**'            | {'anon=/'} | '/static/'            ",
    "mid-path catch-all only slash    | '/mid/**/end'           | {'anon=/'} | '/mid/end'            ",
    "mid-path catch-all absent        | '/a/**/b'               | {}         | '/a/b'                ",
    "mid-path catch-all unknown       | '/a/**/b'               |            | '/a/b'                ",
    "nested braces in regex           | '/r/{id:[0-9]{3}}/x'    | {}         | '/r/{id}/x'           ",
    "escaped regex with quantifier    | '/r/{v:\\d{1,2}}'       | {}         | '/r/{v}'              ",
    "escaped brace in regex           | '/r/{v:\\{}'            | {}         | '/r/{v}'              ",
    "optional regex never optional    | '/f/{f:(?:\\.json)?}'   | {}         | '/f/{f}'              ",
    "regex with static suffix         | '/f/{name:[a-z]+}.json' | {}         | '/f/{name}.json'      ",
    "regex name sanitized             | '/a/{user-id:\\d+}'     | {}         | '/a/{user_id}'        ",
    "question mark wildcard           | '/a/file?.txt'          | {}         | '/a/file{param1}.txt' ",
    "two question marks               | '/a/??'                 | {}         | '/a/{param1}{param2}' ",
    "star as whole segment            | '/a/*/b'                | {}         | '/a/{param1}/b'       ",
    "double star inside segment       | '/a/x**'                | {}         | '/a/x{param1}'        ",
    "triple star segment              | '/a/***'                | {}         | '/a/{param1}'         ",
    "Ant suffix pattern               | '/users.*'              | {}         | '/users.{param1}'     ",
    "Ant suffix pattern after param   | '/users/{id}.*'         | {}         | '/users/{id}.{param1}'",
    "root                             | '/'                     | {}         | '/'                   ",
    "empty segments ignored           | '//a//b'                | {}         | '/a/b'                ",
    "no leading slash                 | 'users/{id}'            | {}         | '/users/{id}'         ",
    "backslash outside braces literal | '/a\\b'                 | {}         | '/a%5Cb'              ",
    "percent literal                  | '/a%20b'                | {}         | '/a%2520b'            "
  })
  void parsesSpringPatterns(String pattern, Set<String> present, String expected) {
    RouteTemplate template = SpringRouteParser.parse(pattern);
    assertEquals(expected, template.render(null, RfcRouteNotation.presence(present)));
  }

  @TableTest({
    "Scenario               | Pattern          ",
    "null                   |                  ",
    "empty                  | ''               ",
    "unclosed brace         | '/a/{id'         ",
    "unopened brace         | '/a/id}'         ",
    "unclosed nested regex  | '/a/{id:[0-9]{3}'",
    "escaped closing brace  | '/a/{id:\\}'     ",
    "slash inside braces    | '/a/{x:a/b}'     ",
    "empty name             | '/a/{}'          ",
    "regex without name     | '/a/{:\\d+}'     ",
    "catch-all without name | '/a/{*}'         "
  })
  void rejectsPatternsThatCannotBeExpressed(String pattern) {
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, SpringRouteParser.parse(pattern));
  }

  @TableTest({
    "Scenario                      | Pattern             | Needs remainder",
    "trailing **                   | '/files/**'         | true           ",
    "root **                       | '/**'               | true           ",
    "named param before            | '/w/{id}/**'        | true           ",
    "param with static text before | '/w/v{id}.x/**'     | true           ",
    "unnamed * before              | '/a/*/**'           | false          ",
    "unnamed ? before              | '/a/file?/**'       | false          ",
    "regex param before            | '/a/{id:\\d+}/**'   | false          ",
    "regex without wildcard before | '/a/{id:[0-9]+}/**' | false          ",
    "mid-path ** before            | '/a/**/b/**'        | false          ",
    "mid-path ** only              | '/a/**/b'           | false          ",
    "trailing slash                | '/files/**/'        | true           ",
    "trailing slash, * before      | '/a/*/**/'          | false          ",
    "optional-like name before     | '/files/{file?}/**' | false          ",
    "name with * before            | '/files/{f*}/**'    | false          ",
    "named catch-all before        | '/a/{*p}/**'        | false          ",
    "** next to text               | '/files/x**'        | false          ",
    "named catch-all               | '/files/{*path}'    | false          ",
    "no catch-all                  | '/files/{id}'       | false          "
  })
  void decidesWhenTheUnnamedCatchAllRemainderIsUsable(String pattern, boolean needsRemainder) {
    assertEquals(needsRemainder, SpringRouteParser.parse(pattern).needsUnnamedCatchAllRemainder());
  }

  @Test
  void notExpressibleNeedsNoRemainder() {
    assertFalse(RouteTemplate.NOT_EXPRESSIBLE.needsUnnamedCatchAllRemainder());
  }

  @Test
  void regexConstraintIsRecorded() {
    RouteTemplate template = SpringRouteParser.parse("/a/{id:\\d+}/{name}");
    assertTrue(template.params().get(0).hasRegexConstraint());
    assertFalse(template.params().get(1).hasRegexConstraint());
  }

  @Test
  void patternWithoutOptionalPartsIsPreRendered() {
    assertEquals(
        "/path/{id}/param", SpringRouteParser.parse("/path/{id:\\d+}/param").staticRoute());
    assertNull(SpringRouteParser.parse("/files/{*path}").staticRoute());
    assertNull(SpringRouteParser.parse("/static/**").staticRoute());
  }

  @Test
  void rendersWithMountPrefix() {
    assertEquals("/ctx/users/{id}", SpringRouteParser.parse("/users/{id}").render("/ctx", null));
  }
}
