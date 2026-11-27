package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.tabletest.junit.TableTest;

class RatpackRouteParserTest {

  @TableTest({
    "Scenario                     | Description                           | Empty inner | Remainder flag | Tokens             | Remainder   | Expected                              ",
    "optional chain one present   | 'api_security/optional-chain/:x?/:y?' | false       | false          | [x: 1]             |             | '/api_security/optional-chain/{x}'    ",
    "optional chain none          | 'api_security/optional-chain/:x?/:y?' | false       | false          | [:]                |             | '/api_security/optional-chain'        ",
    "optional chain both          | 'api_security/optional-chain/:x?/:y?' | false       | false          | [x: 1, y: 2]       |             | '/api_security/optional-chain/{x}/{y}'",
    "optional chain empty value   | 'api_security/optional-chain/:x?/:y?' | false       | false          | [x: '']            |             | '/api_security/optional-chain'        ",
    "optional segment present     | 'optional-segment/:id/:tab?'          | false       | false          | [id: 1, tab: a]    |             | '/optional-segment/{id}/{tab}'        ",
    "optional segment absent      | 'optional-segment/:id/:tab?'          | false       | false          | [id: 1]            |             | '/optional-segment/{id}'              ",
    "catch-all with rest          | 'api_security/catch-all'              | false       | true           | [:]                | 'a/b/c.txt' | '/api_security/catch-all/{param1}'    ",
    "catch-all only slash         | 'api_security/catch-all'              | false       | true           | [:]                | '/'         | '/api_security/catch-all/'            ",
    "catch-all exact              | 'api_security/catch-all'              | false       | false          | [:]                |             | '/api_security/catch-all'             ",
    "catch-all after named token  | 'files/:id'                           | false       | true           | [id: 1]            | 'a'         | '/files/{id}/{param1}'                ",
    "unnamed regex then rest      | 'files/::\\d+'                        | false       | true           | [:]                | 'a'         | '/files/{param1}/{param2}'            ",
    "unnamed regex then slash     | 'files/::\\d+'                        | false       | true           | [:]                | '/'         | '/files/{param1}/'                    ",
    "join artifact dropped        | 'users/'                              | true        | false          | [:]                |             | '/users'                              ",
    "join artifact dropped once   | 'users//'                             | true        | false          | [:]                |             | '/users/'                             ",
    "declared trailing slash      | 'users/'                              | false       | false          | [:]                |             | '/users/'                             ",
    "trailing slash with rest     | 'users/'                              | false       | true           | [:]                | 'x'         | '/users/{param1}'                     ",
    "path param                   | 'path/:id/param'                      | false       | false          | [id: 1]            |             | '/path/{id}/param'                    ",
    "leading slash                | '/path/:id'                           | false       | false          | [id: 1]            |             | '/path/{id}'                          ",
    "regex removed                | ':id:\\d+'                            | false       | false          | [id: 1]            |             | '/{id}'                               ",
    "optional regex removed       | 'a/:id?:\\d+'                         | false       | false          | [id: 1]            |             | '/a/{id}'                             ",
    "optional regex absent        | 'a/:id?:\\d+'                         | false       | false          | [:]                |             | '/a'                                  ",
    "unnamed regex                | 'files/::\\d+'                        | false       | false          | [:]                |             | '/files/{param1}'                     ",
    "encoded literal              | 'api_security/encoded/caf%C3%A9'      | false       | false          | [:]                |             | '/api_security/encoded/caf%C3%A9'     ",
    "param with suffix            | 'files/:name.json'                    | false       | false          | [name: a]          |             | '/files/{name}.json'                  ",
    "empty regex is literal colon | 'a/:id:/b'                            | false       | false          | [id: 1]            |             | '/a/{id}%3A/b'                        ",
    "colon inside segment literal | 'a:b/::'                              | false       | false          | [:]                |             | '/a%3Ab/%3A%3A'                       ",
    "colon without name literal   | 'a/:-x/:'                             | false       | false          | [:]                |             | '/a/%3A-x/%3A'                        ",
    "empty segments ignored       | 'a//:id'                              | false       | false          | [id: 1]            |             | '/a/{id}'                             ",
    "root get empty               | ''                                    | false       | false          | [:]                |             | '/'                                   ",
    "root get empty, empty inner  | ''                                    | true        | false          | [:]                |             | '/'                                   ",
    "token then colon at end      | 'a/:id:'                              | false       | false          | [id: 1]            |             | '/a/{id}%3A'                          ",
    "double colon alone literal   | 'x/::/y'                              | false       | false          | [:]                |             | '/x/%3A%3A/y'                         ",
    "regex may contain colons     | 'a/:id:x:y'                           | false       | false          | [id: 1]            |             | '/a/{id}'                             ",
    "word chars in name           | 'a/:user_Id2'                         | false       | false          | [user_Id2: 1]      |             | '/a/{user_Id2}'                       ",
    "non word char ends name      | 'a/:id-x'                             | false       | false          | [id: 1]            |             | '/a/{id}-x'                           ",
    "invalid escape kept          | 'a%zz'                                | false       | false          | [:]                |             | '/a%25zz'                             ",
    "truncated escape kept        | 'a%4'                                 | false       | false          | [:]                |             | '/a%254'                              ",
    "encoded space round trip     | 'a%20b'                               | false       | false          | [:]                |             | '/a%20b'                              ",
    "encoded slash stays literal  | 'a%2Fb/:id'                           | false       | false          | [id: 1]            |             | '/a%2Fb/{id}'                         ",
    "prefix token and inner token | ':tenant/items/:id'                   | false       | false          | [tenant: t, id: 1] |             | '/{tenant}/items/{id}'                ",
    "duplicate token names        | ':id/x/:id'                           | false       | false          | [id: 1]            |             | '/{id}/x/{id}'                        ",
    "optional in middle present   | ':foo/:bar?/baz'                      | false       | false          | [foo: 1, bar: 2]   |             | '/{foo}/{bar}/baz'                    ",
    "optional in middle absent    | ':foo/:bar?/baz'                      | false       | false          | [foo: 1]           |             | '/{foo}/baz'                          ",
    "optional unknown presence    | 'a/:x?'                               | false       | false          |                    |             | '/a'                                  ",
    "rest after present optional  | 'a/:x?'                               | false       | true           | [x: 1]             | 'b'         | '/a/{x}/{param1}'                     ",
    "rest after absent optional   | 'a/:x?'                               | false       | true           | [:]                | 'b'         | '/a/{param1}'                         ",
    "rest of several segments     | 'api_security/catch-all'              | false       | true           | [:]                | 'a/b'       | '/api_security/catch-all/{param1}'    ",
    "rest of only slashes         | 'api_security/catch-all'              | false       | true           | [:]                | '//'        | '/api_security/catch-all/'            ",
    "rest under empty inner path  | 'files/'                              | true        | true           | [:]                | 'a'         | '/files/{param1}'                     ",
    "several unnamed regexes      | '::\\d+/::\\w+'                       | false       | false          | [:]                |             | '/{param1}/{param2}'                  ",
    "unnamed after taken name     | ':param1/::\\d+'                      | false       | false          | [param1: 1]        |             | '/{param1}/{param2}'                  "
  })
  void parsesRatpackDescriptions(
      String description,
      boolean emptyInnerPath,
      boolean hasRemainder,
      Map<String, String> tokens,
      String remainder,
      String expected) {
    RouteTemplate template = RatpackRouteParser.parse(description, emptyInnerPath, hasRemainder);
    assertEquals(
        expected, template.render(null, RatpackPresenceResolver.of(template, tokens, remainder)));
  }

  @TableTest({
    "Scenario                   | Description    ",
    "null                       |                ",
    "optional token with suffix | 'a/:fmt?.json' ",
    "optional token with colon  | 'a/:fmt?:'     ",
    "optional after required    | ':id/:id?'     ",
    "required after optional    | ':id?/x/:id'   ",
    "two optionals same name    | ':id?/:id?'    ",
    "optional regex same name   | ':id/:id?:\\d+'"
  })
  void rejectsDescriptionsThatCannotBeExpressed(String description) {
    RouteTemplate template = RatpackRouteParser.parse(description, false, false);
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, template);
    assertNull(template.render(null, null));
  }
}
