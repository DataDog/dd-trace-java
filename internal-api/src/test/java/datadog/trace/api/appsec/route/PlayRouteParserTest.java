package datadog.trace.api.appsec.route;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class PlayRouteParserTest {

  @TableTest({
    "Scenario                          | Path                                                              | Present    | Expected                                             ",
    "two params in one segment         | '/api_security/multi-params-in-segment/$id<[^/]+>.$format<[^/]+>' | {}         | '/api_security/multi-params-in-segment/{id}.{format}'",
    "three params in one segment       | '/api_security/three-params/$a<[^/]+>-$b<[^/]+>-$c<[^/]+>'        | {}         | '/api_security/three-params/{a}-{b}-{c}'             ",
    "last non-slash regex present      | '/api_security/optional-params/$id<[^.]+>'                        | {id}       | '/api_security/optional-params/{id}'                 ",
    "last non-slash regex absent       | '/api_security/optional-params/$id<[^.]+>'                        | {}         | '/api_security/optional-params'                      ",
    "static text before param          | '/api_security/static-text/user-$id<[^/]+>'                       | {}         | '/api_security/static-text/user-{id}'                ",
    "param mid-path                    | '/api_security/version/v$version<[^/]+>/items'                    | {}         | '/api_security/version/v{version}/items'             ",
    "static suffix after param         | '/api_security/files/$name<[^/]+>.json'                           | {}         | '/api_security/files/{name}.json'                    ",
    "catch-all present                 | '/api_security/catch-all/$path<.+>'                               | {path}     | '/api_security/catch-all/{path}'                     ",
    "catch-all matched only slash      | '/api_security/catch-all/$path<.+>'                               | {'path=/'} | '/api_security/catch-all/'                           ",
    "catch-all unknown                 | '/api_security/catch-all/$path<.+>'                               |            | '/api_security/catch-all'                            ",
    "encoded literal kept              | '/api_security/encoded/caf%C3%A9'                                 | {}         | '/api_security/encoded/caf%C3%A9'                    ",
    "lowercase escape normalized       | '/a/caf%c3%a9'                                                    | {}         | '/a/caf%C3%A9'                                       ",
    "encoded slash kept in segment     | '/a/x%2Fy'                                                        | {}         | '/a/x%2Fy'                                           ",
    "static route                      | '/api_security/drafts/preview'                                    | {}         | '/api_security/drafts/preview'                       ",
    "single segment                    | '/waf'                                                            | {}         | '/waf'                                               ",
    "trailing slash                    | '/waf/'                                                           | {}         | '/waf/'                                              ",
    "root                              | '/'                                                               | {}         | '/'                                                  ",
    "split route after addMissingSlash | '/api/foo'                                                        | {}         | '/api/foo'                                           ",
    "no leading slash                  | 'api/$id<[^/]+>'                                                  | {}         | '/api/{id}'                                          ",
    "empty segments ignored            | '//a//b'                                                          | {}         | '/a/b'                                               ",
    "braces are literal text           | '/items/{param}'                                                  | {}         | '/items/%7Bparam%7D'                                 ",
    "malformed escape literal          | '/a/%zz'                                                          | {}         | '/a/%25zz'                                           ",
    "truncated escape literal          | '/a/b%2'                                                          | {}         | '/a/b%252'                                           ",
    "digit regex                       | '/a/$id<\\d+>/b'                                                  | {}         | '/a/{id}/b'                                          ",
    "word regex                        | '/a/$id<\\w+>/b'                                                  | {}         | '/a/{id}/b'                                          ",
    "positive class                    | '/a/$id<[a-zA-Z0-9_-]+>/b'                                        | {}         | '/a/{id}/b'                                          ",
    "bounded quantifier                | '/a/$y<[0-9]{4}>/b'                                               | {}         | '/a/{y}/b'                                           ",
    "catch-all after static text       | '/files/file-$p<.+>'                                              | {p}        | '/files/file-{p}'                                    ",
    "catch-all after static absent     | '/files/file-$p<.+>'                                              | {}         | '/files/file-'                                       ",
    "catch-all then trailing slash     | '/a/$p<.+>/'                                                      | {p}        | '/a/{p}/'                                            ",
    "regex with slash as tail          | '/a/$p<[a-z/]+>'                                                  | {p}        | '/a/{p}'                                             ",
    "repeated param name kept          | '/a/$id<[^/]+>/$id<[^/]+>'                                        | {}         | '/a/{id}/{id}'                                       ",
    "dot class mid-path                | '/a/$v<[a-z.]+>/b'                                                | {}         | '/a/{v}/b'                                           ",
    "star regex present                | '/a/$x<[a-z]*>/b'                                                 | {x}        | '/a/{x}/b'                                           ",
    "star regex absent                 | '/a/$x<[a-z]*>/b'                                                 | {}         | '/a/b'                                               ",
    "zero-min bound regex absent       | '/a/v$x<\\d{0,3}>/b'                                              | {}         | '/a/v/b'                                             ",
    "zero-min open bound present       | '/a/$x<\\w{0,}>'                                                  | {x}        | '/a/{x}'                                             ",
    "exact zero bound absent           | '/a/$x<[^/]{0}>.json'                                             | {}         | '/a/.json'                                           ",
    "non-zero min bound mandatory      | '/a/$x<[0-9]{01,3}>'                                              | {}         | '/a/{x}'                                             ",
    "unicode name sanitized            | '/x/$café<[^/]+>'                                                 | {}         | '/x/{caf_}'                                          ",
    "dollar inside name sanitized      | '/x/$a$b<[^/]+>'                                                  | {}         | '/x/{a_b}'                                           ",
    "underscore and dollar start names | '/x/$_a<[^/]+>/$$b<[^/]+>'                                        | {}         | '/x/{_a}/{_b}'                                       "
  })
  void parsesPlayRoutes(String path, Set<String> present, String expected) {
    RouteTemplate template = PlayRouteParser.parse(path);
    assertEquals(expected, template.render(null, RfcRouteNotation.presence(present)));
  }

  @TableTest({
    "Scenario                        | Path                ",
    "null                            |                     ",
    "empty                           | ''                  ",
    "mid-path catch-all              | '/a/$p<.+>/b'       ",
    "mid-path regex with slash       | '/a/$p<[a-z/]+>/b'  ",
    "catch-all followed by text      | '/a/$p<.+>.json'    ",
    "catch-all followed by param     | '/a/$p<.*>$q<[^/]+>'",
    "unclosed regex                  | '/a/$x<abc'         ",
    "negated class without slash mid | '/a/$id<[^.]+>/b'   ",
    "dollar at the end               | '/x/$'              ",
    "dollar without name             | '/a/$/b'            ",
    "dollar name without regex       | '/a/$id/b'          ",
    "dollar name at the end          | '/a/$id'            ",
    "dollar digit is not a name      | '/x/$1<x>'          ",
    "dollar in static text           | '/a/b$c'            ",
    "name stops at a non-identifier  | '/a/$id-x<[^/]+>'   "
  })
  void rejectsRoutesThatCannotBeExpressed(String path) {
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, PlayRouteParser.parse(path));
  }

  @TableTest({
    "Scenario                          | Regex            | Kind          ",
    "negated slash plus                | '[^/]+'          | PARAM         ",
    "negated slash star                | '[^/]*'          | OPTIONAL_PARAM",
    "negated slash and dot             | '[^/.]+'         | PARAM         ",
    "negated escaped slash             | '[^\\/]+'        | PARAM         ",
    "negated range covering slash      | '[^!-0]+'        | PARAM         ",
    "negated with complement escape    | '[^\\S/]+'       | PARAM         ",
    "negated with escaped dot          | '[^\\./]+'       | PARAM         ",
    "negated without slash             | '[^.]+'          | MULTI_SEGMENT ",
    "negated unknown escape            | '[^\\p/]+'       | MULTI_SEGMENT ",
    "digits                            | '\\d+'           | PARAM         ",
    "word                              | '\\w*'           | OPTIONAL_PARAM",
    "single digit                      | '\\d'            | PARAM         ",
    "non-digit escape                  | '\\D+'           | MULTI_SEGMENT ",
    "trailing backslash                | '\\'             | MULTI_SEGMENT ",
    "positive class                    | '[0-9]+'         | PARAM         ",
    "positive class with dash at end   | '[a-zA-Z0-9_-]+' | PARAM         ",
    "positive class with dash at start | '[-a]+'          | PARAM         ",
    "positive class with class escapes | '[\\d\\w\\s]+'   | PARAM         ",
    "positive class with escaped dash  | '[a\\-z]+'       | PARAM         ",
    "positive class with slash         | '[a-z/]+'        | MULTI_SEGMENT ",
    "positive class with dot           | '[a.]+'          | PARAM         ",
    "positive class with escaped slash | '[a\\/]+'        | MULTI_SEGMENT ",
    "positive class with escaped dot   | '[a\\.]+'        | PARAM         ",
    "positive range covering slash     | '[!-0]+'         | MULTI_SEGMENT ",
    "positive range covering dot       | '[!-.]+'         | PARAM         ",
    "positive class with non-space     | '[\\S]+'         | MULTI_SEGMENT ",
    "positive range escaped end        | '[a-\\z]+'       | MULTI_SEGMENT ",
    "reversed range                    | '[z-a]+'         | MULTI_SEGMENT ",
    "nested class                      | '[a[b]]+'        | MULTI_SEGMENT ",
    "intersection                      | '[a&&b]+'        | MULTI_SEGMENT ",
    "leading closing bracket           | '[]a]+'          | MULTI_SEGMENT ",
    "unclosed class                    | '[a-z'           | MULTI_SEGMENT ",
    "class ends in backslash           | '[a\\'           | MULTI_SEGMENT ",
    "bounded exact                     | '[0-9]{4}'       | PARAM         ",
    "bounded open                      | '[0-9]{4,}'      | PARAM         ",
    "bounded range                     | '[0-9]{1,3}'     | PARAM         ",
    "bounded without digits            | '[0-9]{,3}'      | MULTI_SEGMENT ",
    "bounded two commas                | '[0-9]{1,2,3}'   | MULTI_SEGMENT ",
    "bounded empty                     | '[0-9]{}'        | MULTI_SEGMENT ",
    "bounded unclosed                  | '[0-9]{4'        | MULTI_SEGMENT ",
    "lazy quantifier                   | '[0-9]+?'        | MULTI_SEGMENT ",
    "optional quantifier               | '[0-9]?'         | MULTI_SEGMENT ",
    "two atoms                         | '\\d+\\.\\d+'    | MULTI_SEGMENT ",
    "dot plus                          | '.+'             | MULTI_SEGMENT ",
    "alternation                       | 'json|xml'       | MULTI_SEGMENT ",
    "empty                             | ''               | MULTI_SEGMENT ",
    "zero-min bound                    | '\\d{0,3}'       | OPTIONAL_PARAM",
    "exact zero bound                  | '[^/]{0}'        | OPTIONAL_PARAM",
    "non-zero min bound                | '[0-9]{01,3}'    | PARAM         "
  })
  void classifiesRegexes(String regex, PlayRouteParser.RegexKind kind) {
    assertEquals(kind, PlayRouteParser.classifyRegex(regex, 0, regex.length()));
  }

  @Test
  void catchAllPresenceComesFromBoundValues() {
    RouteTemplate template = PlayRouteParser.parse("/api_security/catch-all/$path<.+>");
    assertEquals(
        "/api_security/catch-all/{path}",
        template.render(
            "", BoundValuesPresenceResolver.of(template, singletonMap("path", "a/b/c.txt"), null)));
    assertEquals(
        "/api_security/catch-all/",
        template.render(
            "", BoundValuesPresenceResolver.of(template, singletonMap("path", "/"), null)));
    assertEquals(
        "/api_security/catch-all",
        template.render("", BoundValuesPresenceResolver.of(template, emptyMap(), null)));
    // no bound values: unknown, so the catch-all is left out rather than assumed present
    assertSame(PresenceResolver.UNKNOWN, BoundValuesPresenceResolver.of(template, null, null));
    assertEquals(
        "/api_security/catch-all",
        template.render("", BoundValuesPresenceResolver.of(template, null, null)));
  }

  @Test
  void regexMatchingEmptyIsOptionalParam() {
    RouteTemplate template = PlayRouteParser.parse("/a/pre-$x<[^/]*>/b");
    assertNull(template.staticRoute());
    assertEquals(
        "/a/pre-{x}/b",
        template.render(
            "", BoundValuesPresenceResolver.of(template, singletonMap("x", "x"), null)));
    assertEquals(
        "/a/pre-/b",
        template.render("", BoundValuesPresenceResolver.of(template, singletonMap("x", ""), null)));

    RouteTemplate whole = PlayRouteParser.parse("/a/$x<[^/]*>/b");
    assertEquals(
        "/a/{x}/b",
        whole.render("", BoundValuesPresenceResolver.of(whole, singletonMap("x", "x"), null)));
    // an empty value leaves the segment empty, which is dropped rather than rendered as "//"
    assertEquals(
        "/a/b",
        whole.render("", BoundValuesPresenceResolver.of(whole, singletonMap("x", ""), null)));
  }

  @Test
  void routeWithoutCatchAllIsPreRendered() {
    assertEquals(
        "/api_security/files/{name}.json",
        PlayRouteParser.parse("/api_security/files/$name<[^/]+>.json").staticRoute());
    assertNull(PlayRouteParser.parse("/api_security/catch-all/$path<.+>").staticRoute());
  }
}
