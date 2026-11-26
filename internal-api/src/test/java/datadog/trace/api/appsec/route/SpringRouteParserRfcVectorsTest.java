package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * The RFC-1125 "Test cases" rows (see {@link NormalizedRouteRfcVectorsTest}) whose route is also
 * valid PathPattern / AntPathMatcher syntax, run through {@link SpringRouteParser}. Rows 15-29 and
 * 39-45 use RFC-only notation ({x?}, [...], (...)) that Spring does not have.
 */
class SpringRouteParserRfcVectorsTest {

  @TableTest({
    "Scenario                                                | Route                         | Hit URL               | Present    | Tag                          ",
    "#1 Basics                                               | '/users'                      | '/users'              | {}         | '/users'                     ",
    "#2 Basics                                               | '/'                           | '/'                   | {}         | '/'                          ",
    "#3 Basics                                               | '/users/{id}'                 | '/users/42'           | {}         | '/users/{id}'                ",
    "#4 Basics                                               | '/users/{id}/posts/{post_id}' | '/users/1/posts/5'    | {}         | '/users/{id}/posts/{post_id}'",
    "#5 Basics                                               | '/users/{id}/'                | '/users/1/'           | {}         | '/users/{id}/'               ",
    "#6 Static text in a segment                             | '/users/user-{id}'            | '/users/user-42'      | {}         | '/users/user-{id}'           ",
    "#7 Static text in a segment                             | '/v{version}/items'           | '/v2/items'           | {}         | '/v{version}/items'          ",
    "#8 Static text in a segment                             | '/files/{name}.json'          | '/files/report.json'  | {}         | '/files/{name}.json'         ",
    "#9 Static text in a segment                             | '/café'                       | '/caf%C3%A9'          | {}         | '/caf%C3%A9'                 ",
    "#10 Static text in a segment                            | '/user:me'                    | '/user:me'            | {}         | '/user%3Ame'                 ",
    "#11 Static text in a segment                            | '/a+b/{id}'                   | '/a+b/1'              | {}         | '/a%2Bb/{id}'                ",
    "#12 Several parameters in one segment                   | '/photos/{id}.{format}'       | '/photos/1.jpg'       | {}         | '/photos/{id}.{format}'      ",
    "#13 Several parameters in one segment                   | '/d/{a}-{b}-{c}'              | '/d/1-2-3'            | {}         | '/d/{a}-{b}-{c}'             ",
    "#14 Several parameters in one segment                   | '/v/{major}{minor}'           | '/v/12'               | {}         | '/v/{major}{minor}'          ",
    "#30 Parameter names                                     | '/users/{user-id}'            | '/users/1'            | {}         | '/users/{user_id}'           ",
    "#31 Parameter names                                     | '/items/{item.id}'            | '/items/1'            | {}         | '/items/{item_id}'           ",
    "#32 Parameter names, collision                          | '/a/{user-id}/{user_id}'      | '/a/1/2'              | {}         | '/a/{user_id_2}/{user_id}'   ",
    "#33 Parameter names, collision (suffix _2 already used) | '/a/{x-y}/{x_y}/{x_y_2}'      | '/a/1/2/3'            | {}         | '/a/{x_y_3}/{x_y}/{x_y_2}'   ",
    "#34 Parameter names, non-ASCII                          | '/u/{föo}'                    | '/u/1'                | {}         | '/u/{f_o}'                   ",
    "#35 Catch-all, named                                    | '/files/{*path}'              | '/files/a/b/c.txt'    | {path}     | '/files/{path}'              ",
    "#36 Catch-all, unnamed                                  | '/static/**'                  | '/static/css/app.css' | {anon}     | '/static/{param1}'           ",
    "#37 Catch-all, unnamed, name already used               | '/w/{param1}/**'              | '/w/x/a/b'            | {anon}     | '/w/{param1}/{param2}'       ",
    "#38 Catch-all, mid-path                                 | '/a/**/b'                     | '/a/x/y/b'            | {anon}     | '/a/{param1}/b'              ",
    "#49 Catch-all, empty value                              | '/files/{*path}'              | '/files/'             | {'path=/'} | '/files/'                    ",
    "#50 Wildcard in a segment, unnamed                      | '/files/*.txt'                | '/files/report.txt'   | {}         | '/files/{param1}.txt'        "
  })
  void rendersRfcRows(String route, String hitUrl, Set<String> present, String tag) {
    assertEquals(
        tag,
        SpringRouteParser.parse(route).render(null, RfcRouteNotation.presence(present)),
        hitUrl);
  }

  @TableTest({
    "Scenario              | Prefix | Route | Present    | Expected",
    "root catch-all absent | '/ctx' | '/**' | {}         | '/ctx'  ",
    "root catch-all, slash | '/ctx' | '/**' | {'anon=/'} | '/ctx/' "
  })
  void absentRouteBodyRendersThePrefixAlone(
      String prefix, String route, Set<String> present, String expected) {
    assertEquals(
        expected,
        SpringRouteParser.parse(route).render(prefix, RfcRouteNotation.presence(present)));
  }

  @Test
  void rootRouteKeepsItsSlashUnderAPrefix() {
    assertEquals("/ctx/", SpringRouteParser.parse("/").render("/ctx", null));
    assertEquals("/", SpringRouteParser.parse("/").render(null, null));
  }
}
