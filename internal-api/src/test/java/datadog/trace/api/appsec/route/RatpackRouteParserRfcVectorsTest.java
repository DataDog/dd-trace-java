package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.tabletest.junit.TableTest;

/**
 * The RFC-1125 "Test cases" rows (see {@link NormalizedRouteRfcVectorsTest}) that can be declared
 * with Ratpack path binders, run through {@link RatpackRouteParser} with the presence Ratpack
 * exposes: {@code PathBinding.getAllTokens()} ({@code Tokens}) and the past binding of a {@code
 * prefix(x){all{}}} catch-all ({@code Remainder}, empty when the request hit the prefix exactly).
 * The route column is the {@code PathBinding.getDescription()} Ratpack builds for the RFC route.
 *
 * <p>Rows that are not applicable to Ratpack:
 *
 * <ul>
 *   <li>6, 7: static text before a parameter in one segment; a Ratpack token only starts a segment.
 *   <li>12-14: several parameters in one segment; only the segment start can be a token.
 *   <li>17-20, 29: optional text inside a segment; {@code :x?} must start a segment, and an
 *       optional token followed by text is not expressible.
 *   <li>26, 27, 40-45: optional static text and optional groups; Ratpack has no such syntax.
 *   <li>28: presence that cannot be determined; Ratpack always exposes the bound tokens.
 *   <li>30-34: invalid or non-ASCII parameter names; Ratpack names are {@code \w+}, so {@code -},
 *       {@code .} or a non-ASCII letter ends the name and starts static text.
 *   <li>35: named catch-all; the Ratpack catch-all is the unnamed {@code prefix(x){all{}}}
 *       remainder (see 36 and 49).
 *   <li>38, 50: catch-all in the middle of a path and wildcard followed by text in a segment; a
 *       {@code ::regex} token takes the whole segment.
 *   <li>46-48: Go-specific rows (gin parameter syntax, ServeMux prefix routes).
 * </ul>
 */
class RatpackRouteParserRfcVectorsTest {

  @TableTest({
    "Scenario                                  | Description                | Empty inner | Remainder flag | Tokens              | Remainder     | Hit URL               | Tag                          ",
    "#1 Basics                                 | 'users'                    | false       | false          | [:]                 |               | '/users'              | '/users'                     ",
    "#2 Basics                                 | ''                         | false       | false          | [:]                 |               | '/'                   | '/'                          ",
    "#3 Basics                                 | 'users/:id'                | false       | false          | [id: 42]            |               | '/users/42'           | '/users/{id}'                ",
    "#4 Basics                                 | 'users/:id/posts/:post_id' | false       | false          | [id: 1, post_id: 5] |               | '/users/1/posts/5'    | '/users/{id}/posts/{post_id}'",
    "#5 Basics                                 | 'users/:id/'               | false       | false          | [id: 1]             |               | '/users/1/'           | '/users/{id}/'               ",
    "#8 Static text in a segment               | 'files/:name.json'         | false       | false          | [name: report]      |               | '/files/report.json'  | '/files/{name}.json'         ",
    "#9 Static text in a segment (encoded)     | 'caf%C3%A9'                | false       | false          | [:]                 |               | '/caf%C3%A9'          | '/caf%C3%A9'                 ",
    "#9 Static text in a segment (raw)         | 'café'                     | false       | false          | [:]                 |               | '/caf%C3%A9'          | '/caf%C3%A9'                 ",
    "#10 Static text in a segment              | 'user:me'                  | false       | false          | [:]                 |               | '/user:me'            | '/user%3Ame'                 ",
    "#11 Static text in a segment              | 'a+b/:id'                  | false       | false          | [id: 1]             |               | '/a+b/1'              | '/a%2Bb/{id}'                ",
    "#15 Optional, present                     | 'users/:id/:tab?'          | false       | false          | [id: 1, tab: posts] |               | '/users/1/posts'      | '/users/{id}/{tab}'          ",
    "#16 Optional, absent                      | 'users/:id/:tab?'          | false       | false          | [id: 1]             |               | '/users/1'            | '/users/{id}'                ",
    "#21 Optional, absent                      | 'a/:x?/:y?'                | false       | false          | [:]                 |               | '/a'                  | '/a'                         ",
    "#22 Optional, present                     | 'a/:x?/:y?'                | false       | false          | [x: 1]              |               | '/a/1'                | '/a/{x}'                     ",
    "#23 Optional, present                     | 'a/:x?/:y?'                | false       | false          | [x: 1, y: 2]        |               | '/a/1/2'              | '/a/{x}/{y}'                 ",
    "#24 Optional, absent                      | 'posts/:page?'             | false       | false          | [:]                 |               | '/posts'              | '/posts'                     ",
    "#25 Optional, present                     | 'posts/:page?'             | false       | false          | [page: 1]           |               | '/posts/1'            | '/posts/{page}'              ",
    "#36 Catch-all, unnamed                    | 'static'                   | false       | true           | [:]                 | 'css/app.css' | '/static/css/app.css' | '/static/{param1}'           ",
    "#37 Catch-all, unnamed, name already used | 'w/:param1'                | false       | true           | [param1: x]         | 'a/b'         | '/w/x/a/b'            | '/w/{param1}/{param2}'       ",
    "#39 Catch-all, optional, absent           | 'files'                    | false       | false          | [:]                 |               | '/files'              | '/files'                     ",
    "#49 Catch-all, empty value                | 'files'                    | false       | true           | [:]                 | '/'           | '/files/'             | '/files/'                    "
  })
  void rendersRfcRows(
      String description,
      boolean emptyInnerPath,
      boolean hasRemainder,
      Map<String, String> tokens,
      String remainder,
      String hitUrl,
      String tag) {
    assertRenders(description, emptyInnerPath, hasRemainder, tokens, remainder, hitUrl, tag);
  }

  /** RFC-1125 rules that have no numbered row but have Ratpack syntax. */
  @TableTest({
    "Scenario                       | Description       | Tokens  | Hit URL    | Tag              ",
    "regex constraint removed       | 'users/:id:\\d+'  | [id: 1] | '/users/1' | '/users/{id}'    ",
    "optional regex constraint gone | 'users/:id?:\\d+' | [id: 1] | '/users/1' | '/users/{id}'    ",
    "unnamed parameter is param<N>  | 'users/::\\d+'    | [:]     | '/users/1' | '/users/{param1}'"
  })
  void rendersRfcRules(String description, Map<String, String> tokens, String hitUrl, String tag) {
    assertRenders(description, false, false, tokens, null, hitUrl, tag);
  }

  private static void assertRenders(
      String description,
      boolean emptyInnerPath,
      boolean hasRemainder,
      Map<String, String> tokens,
      String remainder,
      String hitUrl,
      String tag) {
    RouteTemplate template = RatpackRouteParser.parse(description, emptyInnerPath, hasRemainder);
    assertEquals(
        tag,
        template.render(null, RatpackPresenceResolver.of(template, tokens, remainder)),
        hitUrl);
  }
}
