package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.appsec.route.VertxRouteParser.Grammar;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class VertxRouteParserTest {

  @TableTest({
    "Scenario                          | Path                      | Expected                 ",
    "two params with dot               | '/a/:id.:format'          | '/a/{id}.{format}'       ",
    "three params with dashes          | '/three/:a-:b-:c'         | '/three/{a}-{b}-{c}'     ",
    "static prefix in segment          | '/u/user-:id'             | '/u/user-{id}'           ",
    "param inside first segment        | '/v:version/items'        | '/v{version}/items'      ",
    "static suffix after dot           | '/files/:name.json'       | '/files/{name}.json'     ",
    "suffix param                      | '/search.:format'         | '/search.{format}'       ",
    "adjacent params                   | '/v/:major:minor'         | '/v/{major}{minor}'      ",
    "root                              | '/'                       | '/'                      ",
    "trailing slash                    | '/x/'                     | '/x/'                    ",
    "param and trailing slash          | '/x/:id/'                 | '/x/{id}/'               ",
    "empty segments ignored            | '//a//:id'                | '/a/{id}'                ",
    "encoded literal                   | '/caf%C3%A9'              | '/caf%C3%A9'             ",
    "raw non-ASCII literal             | '/café'                   | '/caf%C3%A9'             ",
    "encoded spaces                    | '/encoded%20path%20query' | '/encoded%20path%20query'",
    "encoded literal next to param     | '/caf%C3%A9/:id'          | '/caf%C3%A9/{id}'        ",
    "escaped regex chars are literals  | '/a+b/(x)/:id$'           | '/a%2Bb/%28x%29/{id}%24' ",
    "colon alone                       | '/a/:'                    | '/a/%3A'                 ",
    "colon at end of segment           | '/a:/b'                   | '/a%3A/b'                ",
    "plain path, mid star literal      | '/a/*/b'                  | '/a/%2A/b'               ",
    "plain path, question mark literal | '/a/b?'                   | '/a/b%3F'                ",
    "plain path, braces literal        | '/a/{b}'                  | '/a/%7Bb%7D'             ",
    "plain path, double star unknown   | '/a/**'                   | '/a/%2A'                 "
  })
  void parsesDefaultGrammarPaths(String path, String expected) {
    assertEquals(
        expected,
        VertxRouteParser.parse(path, Grammar.DEFAULT).render(null, PresenceResolver.UNKNOWN));
  }

  @TableTest({
    "Scenario                         | Path           | Present    | Expected              ",
    "whole-segment catch-all present  | '/a/*'         | {anon}     | '/a/{param1}'         ",
    "whole-segment catch-all absent   | '/a/*'         | {}         | '/a'                  ",
    "whole-segment catch-all unknown  | '/a/*'         |            | '/a'                  ",
    "whole-segment catch-all slash    | '/a/*'         | {'anon=/'} | '/a/'                 ",
    "in-segment catch-all present     | '/img*'        | {anon}     | '/img{param1}'        ",
    "in-segment catch-all absent      | '/img*'        | {}         | '/img'                ",
    "in-segment catch-all slash       | '/img*'        | {'anon=/'} | '/img'                ",
    "root catch-all present           | '/*'           | {anon}     | '/{param1}'           ",
    "root catch-all absent            | '/*'           | {}         | '/'                   ",
    "root catch-all slash             | '/*'           | {'anon=/'} | '/'                   ",
    "catch-all after param            | '/users/:id/*' | {anon}     | '/users/{id}/{param1}'",
    "catch-all after param absent     | '/users/:id/*' | {}         | '/users/{id}'         ",
    "catch-all after param1 name      | '/w/:param1/*' | {anon}     | '/w/{param1}/{param2}'",
    "catch-all after in-segment param | '/f/:id*'      | {anon}     | '/f/{id}{param1}'     ",
    "plain double star, present       | '/a/**'        | {anon}     | '/a/%2A{param1}'      "
  })
  void parsesTrailingCatchAll(String path, Set<String> present, String expected) {
    assertEquals(
        expected,
        VertxRouteParser.parse(path, Grammar.DEFAULT)
            .render(null, RfcRouteNotation.presence(present)));
  }

  @TableTest({
    "Scenario                        | Grammar  | Path       | Expected    ",
    "digit-first name, legacy        | LEGACY   | '/t/:1abc' | '/t/%3A1abc'",
    "digit-first name, default       | DEFAULT  | '/t/:1abc' | '/t/{1abc}' ",
    "digit-first name, extended      | EXTENDED | '/t/:1abc' | '/t/%3A1abc'",
    "letter name, legacy             | LEGACY   | '/t/:id'   | '/t/{id}'   ",
    "underscore-first name, legacy   | LEGACY   | '/t/:_id'  | '/t/%3A_id' ",
    "underscore-first name, default  | DEFAULT  | '/t/:_id'  | '/t/{_id}'  ",
    "underscore-first name, extended | EXTENDED | '/t/:_id'  | '/t/{_id}'  ",
    "dash ends name, legacy          | LEGACY   | '/t/:id-x' | '/t/{id}-x' ",
    "dash ends name, default         | DEFAULT  | '/t/:id-x' | '/t/{id}-x' ",
    "dash in name, extended          | EXTENDED | '/t/:id-x' | '/t/{id_x}' ",
    "dollar never starts a name      | EXTENDED | '/t/:$a'   | '/t/%3A%24a'",
    "dollar never starts a name      | DEFAULT  | '/t/:$a'   | '/t/%3A%24a'",
    "dollar ends a name, extended    | EXTENDED | '/t/:a$b'  | '/t/{a}%24b'",
    "colon with dash, extended       | EXTENDED | '/t/:a-:b' | '/t/{a_}{b}'"
  })
  void nameGrammarDependsOnTheVersion(Grammar grammar, String path, String expected) {
    assertEquals(
        expected, VertxRouteParser.parse(path, grammar).render(null, PresenceResolver.UNKNOWN));
  }

  @Test
  void extendedNameKeepsItsFrameworkName() {
    RouteTemplate template = VertxRouteParser.parse("/t/:a-:b", Grammar.EXTENDED);
    assertEquals("a-", template.params().get(0).frameworkName());
    assertEquals("a_", template.params().get(0).name());
    assertEquals("b", template.params().get(1).frameworkName());
  }

  @TableTest({
    "Scenario                     | Path          ",
    "null                         |               ",
    "empty                        | ''            ",
    "relative                     | 'abc'         ",
    "relative param               | 'a/:id'       ",
    "optional param               | '/x/:id?'     ",
    "character class              | '/x/:id[0-9]' ",
    "alternation                  | '/x/:id|y'    ",
    "mid star in param path       | '/a/*/:id'    ",
    "star in param segment        | '/a/:id*x'    ",
    "double trailing star         | '/a/:id/**'   ",
    "quantifier                   | '/x/:id{2}'   ",
    "backslash escape             | '/x/:id\\d'   ",
    "anchor                       | '/x/^:id'     ",
    "regex char with invalid name | '/x/:1?'      ",
    "duplicate name               | '/x/:id/y/:id'"
  })
  void rejectsPathsThatCannotBeExpressed(String path) {
    for (Grammar grammar : Grammar.values()) {
      assertSame(
          RouteTemplate.NOT_EXPRESSIBLE, VertxRouteParser.parse(path, grammar), grammar.name());
    }
  }

  @Test
  void nullGrammarIsNotExpressible() {
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, VertxRouteParser.parse("/a", null));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, VertxRouteParser.cached("/a", null));
  }

  @Test
  void encodedSlashInLiteralStaysInItsSegment() {
    assertEquals(
        "/a%2Fb/{id}", VertxRouteParser.parse("/a%2Fb/:id", Grammar.DEFAULT).render(null, null));
  }

  @Test
  void routesWithoutCatchAllArePreRendered() {
    assertEquals(
        "/a/{id}.{format}",
        VertxRouteParser.parse("/a/:id.:format", Grammar.DEFAULT).staticRoute());
    assertNull(VertxRouteParser.parse("/a/*", Grammar.DEFAULT).staticRoute());
  }

  @Test
  void trailingWholeSegmentCatchAllNeedsTheRemainder() {
    assertTrue(VertxRouteParser.parse("/a/*", Grammar.DEFAULT).needsUnnamedCatchAllRemainder());
    assertTrue(
        VertxRouteParser.parse("/users/:id/*", Grammar.DEFAULT).needsUnnamedCatchAllRemainder());
    assertFalse(VertxRouteParser.parse("/img*", Grammar.DEFAULT).needsUnnamedCatchAllRemainder());
  }

  @TableTest({
    "Scenario                     | Grammar  | Path             | Name   | Declared",
    "single param                 | DEFAULT  | '/users/:id'     | id     | true    ",
    "second param in segment      | DEFAULT  | '/a/:id.:format' | format | true    ",
    "name prefix of a longer name | DEFAULT  | '/a/:idx'        | id     | false   ",
    "longer name than declared    | DEFAULT  | '/a/:id'         | idx    | false   ",
    "name before a trailing star  | DEFAULT  | '/a/:id*'        | id     | true    ",
    "undeclared name              | DEFAULT  | '/users/:id'     | tenant | false   ",
    "no param                     | DEFAULT  | '/users'         | id     | false   ",
    "colon without a name         | LEGACY   | '/a/:1/:id'      | id     | true    ",
    "digit name, legacy           | LEGACY   | '/a/:1abc'       | 1abc   | false   ",
    "digit name, default          | DEFAULT  | '/a/:1abc'       | 1abc   | true    ",
    "dashed name, extended        | EXTENDED | '/a/:a-b'        | a-b    | true    ",
    "dashed name, default         | DEFAULT  | '/a/:a-b'        | a-b    | false   ",
    "empty name                   | DEFAULT  | '/a/:id'         | ''     | false   ",
    "null name                    | DEFAULT  | '/a/:id'         |        | false   ",
    "null path                    | DEFAULT  |                  | id     | false   ",
    "null grammar                 |          | '/a/:id'         | id     | false   "
  })
  void findsDeclaredParams(Grammar grammar, String path, String name, boolean declared) {
    assertEquals(declared, VertxRouteParser.declaresParam(path, name, grammar));
  }

  @Test
  void findsTheEndOfAParamName() {
    assertEquals(6, VertxRouteParser.nameEnd("/a/:id.json", 3, 11, Grammar.DEFAULT));
    assertEquals(5, VertxRouteParser.nameEnd("/a/:id.json", 3, 5, Grammar.DEFAULT));
    assertEquals(-1, VertxRouteParser.nameEnd("/a/:", 3, 4, Grammar.DEFAULT));
    assertEquals(-1, VertxRouteParser.nameEnd("/a/:.x", 3, 6, Grammar.DEFAULT));
  }

  @Test
  void rendersWithMountPrefix() {
    assertEquals(
        "/sub/users/{id}",
        VertxRouteParser.parse("/users/:id", Grammar.DEFAULT).render("/sub/", null));
  }
}
