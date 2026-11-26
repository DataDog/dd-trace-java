package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class JaxRsRouteParserTest {

  @TableTest({
    "Scenario                         | Route                             | Present    | Expected                         ",
    "QA static text after param       | '/api_security/files/{name}.json' | {}         | '/api_security/files/{name}.json'",
    "QA regex with nested braces      | '/u/{id: [0-9]{3}}'               | {}         | '/u/{id}'                        ",
    "nested braces mid route          | '/a/{id: [0-9]{3}}/b'             | {}         | '/a/{id}/b'                      ",
    "whitespace around name and regex | '/a/{ id : \\d+ }'                | {}         | '/a/{id}'                        ",
    "whitespace without regex         | '/a/{ id }'                       | {}         | '/a/{id}'                        ",
    "empty regex                      | '/a/{id:}'                        | {}         | '/a/{id}'                        ",
    "escaped brace in regex           | '/a/{v: \\{}'                     | {}         | '/a/{v}'                         ",
    "slash inside regex, present      | '/a/{p: [a-z/]+}/b'               | {p}        | '/a/{p}/b'                       ",
    "slash inside regex, absent       | '/a/{p: [a-z/]+}/b'               | {}         | '/a/b'                           ",
    "several params in one segment    | '/d/{a}-{b}-{c}'                  | {}         | '/d/{a}-{b}-{c}'                 ",
    "static text before param         | '/users/user-{id}'                | {}         | '/users/user-{id}'               ",
    "version prefix                   | '/v{version}/items'               | {}         | '/v{version}/items'              ",
    "name sanitized                   | '/users/{user-id}'                | {}         | '/users/{user_id}'               ",
    "lowercase escape uppercased      | '/caf%c3%a9'                      | {}         | '/caf%C3%A9'                     ",
    "space escapes kept               | '/encoded%20path%20query'         | {}         | '/encoded%20path%20query'        ",
    "non-ASCII literal                | '/café'                           | {}         | '/caf%C3%A9'                     ",
    "percent without escape           | '/100%'                           | {}         | '/100%25'                        ",
    "percent with one hex digit       | '/a%2'                            | {}         | '/a%252'                         ",
    "percent with non-hex digits      | '/a%zz'                           | {}         | '/a%25zz'                        ",
    "escaped slash stays in segment   | '/a%2Fb'                          | {}         | '/a%2Fb'                         ",
    "unreserved escape decoded        | '/a%7Eb'                          | {}         | '/a~b'                           ",
    "catch-all mid segment, present   | '/files/{path: .*}.json'          | {path}     | '/files/{path}.json'             ",
    "catch-all mid segment, absent    | '/files/{path: .*}.json'          | {}         | '/files/.json'                   ",
    "catch-all only slash             | '/catch-all/{path: .*}'           | {'path=/'} | '/catch-all/'                    ",
    "catch-all unknown                | '/catch-all/{path: .*}'           |            | '/catch-all'                     ",
    "root                             | '/'                               | {}         | '/'                              ",
    "empty segments ignored           | '//a//b'                          | {}         | '/a/b'                           ",
    "trailing slash kept              | '/a/{id}/'                        | {}         | '/a/{id}/'                       ",
    "no leading slash                 | 'users/{id}'                      | {}         | '/users/{id}'                    ",
    "colon in literal                 | '/user:me'                        | {}         | '/user%3Ame'                     "
  })
  void parsesJaxRsRoutes(String route, Set<String> present, String expected) {
    RouteTemplate template = JaxRsRouteParser.parse(route);
    assertEquals(expected, template.render(null, RfcRouteNotation.presence(present)));
  }

  @TableTest({
    "Scenario               | Route             ",
    "null                   |                   ",
    "empty                  | ''                ",
    "unclosed brace         | '/a/{id'          ",
    "unopened brace         | '/a/id}'          ",
    "unclosed nested regex  | '/a/{id: [0-9]{3}'",
    "escaped closing brace  | '/a/{id: \\}'     ",
    "empty name             | '/a/{}'           ",
    "blank name             | '/a/{  }'         ",
    "regex without name     | '/a/{: \\d+}'     ",
    "invalid name character | '/a/{*path}'      ",
    "whitespace inside name | '/a/{a b}'        "
  })
  void rejectsRoutesThatCannotBeExpressed(String route) {
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, JaxRsRouteParser.parse(route));
  }

  /**
   * Braces are balanced without regard to regex character classes, so a {@code }} inside one closes
   * the parameter early: the route fails closed (no tag) rather than being misparsed.
   */
  @Test
  void closingBraceInsideARegexClassIsNotExpressible() {
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, JaxRsRouteParser.parse("/a/{id: [}a]+}"));
  }

  @TableTest({
    "Scenario                           | Regex                        | Accepts slash",
    "dot star                           | '.*'                         | true         ",
    "dot plus                           | '.+'                         | true         ",
    "negated class without slash        | '[^/]+'                      | false        ",
    "negated class with escaped slash   | '[^\\/]+'                    | false        ",
    "class with slash                   | '[a-z/]+'                    | true         ",
    "class with escaped slash           | '[a-z\\/]+'                  | true         ",
    "digits                             | '\\d+'                       | false        ",
    "non-whitespace                     | '\\S+'                       | true         ",
    "non-word                           | '\\W+'                       | true         ",
    "non-digit                          | '\\D+'                       | true         ",
    "non-whitespace inside class        | '[\\S]+'                     | true         ",
    "non-word inside class              | '[a\\W]+'                    | true         ",
    "non-digit inside class             | '[\\D]+'                     | true         ",
    "negated non-whitespace class       | '[^\\S]+'                    | false        ",
    "range over slash                   | '[!-~]+'                     | true         ",
    "range below slash                  | '[!-.]+'                     | false        ",
    "range above slash                  | '[0-9]+'                     | false        ",
    "range with escaped end             | '[!-\\~]+'                   | true         ",
    "escaped dot in optional group      | '(?:\\.json)?'               | false        ",
    "quantifier braces                  | '[0-9]{3}'                   | false        ",
    "negated class without slash char   | '[^a]+'                      | true         ",
    "word chars                         | '\\w+'                       | false        ",
    "word chars inside class            | '[\\w\\s\\d-]+'              | false        ",
    "literal slash                      | 'a/b'                        | true         ",
    "escaped literal slash              | 'a\\/b'                      | true         ",
    "escaped backslash                  | '\\\\'                       | false        ",
    "trailing backslash                 | 'a\\'                        | false        ",
    "dash at class start                | '[-a]+'                      | false        ",
    "dash at class end                  | '[a-]+'                      | false        ",
    "escaped char in class              | '[\\-\\]]+'                  | false        ",
    "unterminated class                 | '[a-z'                       | true         ",
    "nested class with slash            | '[a[/]]'                     | true         ",
    "quote with slash                   | '\\Qa/b\\E'                  | true         ",
    "quote without slash                | '\\Qa.b\\E'                  | false        ",
    "unterminated quote                 | '\\Qa.b'                     | false        ",
    "punctuation property               | '\\p{Punct}+'                | true         ",
    "alpha property                     | '\\p{Alpha}+'                | false        ",
    "one-letter property                | '\\pL+'                      | false        ",
    "unterminated property              | '\\p{Alpha'                  | false        ",
    "negated property                   | '\\P{Alpha}+'                | true         ",
    "property inside class              | '[\\p{ASCII}]'               | true         ",
    "alpha property inside class        | '[\\p{L}]'                   | false        ",
    "negated property inside class      | '[\\PL]'                     | true         ",
    "hex escape of slash                | '\\x2F+'                     | true         ",
    "lowercase hex escape of slash      | '\\x2f+'                     | true         ",
    "hex escape of a letter             | '\\x41+'                     | false        ",
    "braced hex escape of slash         | '\\x{2F}+'                   | true         ",
    "braced hex escape of a letter      | '\\x{41}+'                   | false        ",
    "empty braced hex escape            | '\\x{}'                      | true         ",
    "malformed hex escape               | '\\xZZ'                      | true         ",
    "unicode escape of slash            | '\\u002F+'                   | true         ",
    "unicode escape of a letter         | '\\u0041+'                   | false        ",
    "octal escape of slash              | '\\057+'                     | true         ",
    "three-digit octal escape of slash  | '\\0057+'                    | true         ",
    "octal escape of a letter           | '\\0101+'                    | false        ",
    "control escape of slash            | '\\co'                       | true         ",
    "named slash                        | '\\N{SOLIDUS}+'              | true         ",
    "named letter                       | '\\N{LATIN SMALL LETTER A}+' | false        ",
    "hex escape of slash in class       | '[\\x2F]+'                   | true         ",
    "braced hex escape in class         | '[\\x{2F}]+'                 | true         ",
    "unicode escape of slash in class   | '[\\u002F]+'                 | true         ",
    "octal escape of slash in class     | '[\\057]+'                   | true         ",
    "named slash in class               | '[\\N{SOLIDUS}]+'            | true         ",
    "negated class without hex slash    | '[^\\x2F]+'                  | false        ",
    "range with hex end                 | '[!-\\x7E]+'                 | true         ",
    "range with unicode end             | '[!-\\u007E]+'               | true         ",
    "range with octal end               | '[!-\\0176]+'                | true         ",
    "range with tab start               | '[\\t-~]+'                   | true         ",
    "hex range above slash              | '[\\x30-\\x39]+'             | false        ",
    "range with named end               | '[!-\\N{TILDE}]+'            | true         ",
    "non-horizontal-whitespace          | '\\H+'                       | true         ",
    "non-vertical-whitespace            | '\\V+'                       | true         ",
    "grapheme cluster                   | '\\X+'                       | true         ",
    "non-horizontal-whitespace in class | '[\\H]+'                     | true         ",
    "horizontal whitespace              | '\\h+'                       | false        ",
    "vertical whitespace                | '\\v+'                       | false        ",
    "linebreak                          | '\\R'                        | false        ",
    "unknown letter escape              | '\\y'                        | true         ",
    "back reference                     | '(a)\\1'                     | false        ",
    "named back reference               | '(?<n>a)\\k<n>'              | false        ",
    "negated punctuation property       | '\\P{Punct}+'                | false        ",
    "negated ASCII property in class    | '[\\P{ASCII}]+'              | false        ",
    "common script property             | '\\p{IsCommon}+'             | true         ",
    "other punctuation category         | '\\p{gc=Po}+'                | true         ",
    "alternation of literals            | 'json|xml'                   | false        "
  })
  void detectsRegexesThatAcceptSlash(String regex, boolean acceptsSlash) {
    assertEquals(acceptsSlash, JaxRsRouteParser.acceptsSlash(regex));
  }

  @Test
  void regexParamKindsAreRecorded() {
    RouteTemplate template = JaxRsRouteParser.parse("/a/{id: \\d+}/{name}/{rest: .*}");
    RoutePart.Param id = template.params().get(0);
    RoutePart.Param name = template.params().get(1);
    RoutePart.Param rest = template.params().get(2);
    assertTrue(id.hasRegexConstraint());
    assertFalse(id.isOptional());
    assertFalse(name.hasRegexConstraint());
    assertTrue(rest.isCatchAll());
  }

  @Test
  void routeWithoutCatchAllIsPreRendered() {
    assertEquals("/a/{id}/b", JaxRsRouteParser.parse("/a/{id: [0-9]{3}}/b").staticRoute());
    assertNull(JaxRsRouteParser.parse("/files/{path: .*}").staticRoute());
  }

  @Test
  void rendersWithMountPrefix() {
    assertEquals("/ctx/users/{id}", JaxRsRouteParser.parse("/users/{id}").render("/ctx", null));
    assertEquals("/ctx/", JaxRsRouteParser.parse("/").render("/ctx", null));
  }
}
