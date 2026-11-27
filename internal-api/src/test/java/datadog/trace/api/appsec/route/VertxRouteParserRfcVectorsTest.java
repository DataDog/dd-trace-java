package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.appsec.route.VertxRouteParser.Grammar;
import java.util.Set;
import org.tabletest.junit.TableTest;

/**
 * The RFC-1125 "Test cases" rows (see {@link NormalizedRouteRfcVectorsTest}) that Vert.x syntax can
 * express, translated to {@code :name} paths and run through {@link VertxRouteParser}. Vert.x has
 * no optional params or groups (rows 15-29, 39-45 as written), no regex constraints, no named
 * catch-all (35), no {@code .} or non-ASCII names (31, 34) and no static {@code :} next to a name
 * char (10). Catch-all rows use the unnamed trailing {@code *} instead (36, 37, 39, 49).
 */
class VertxRouteParserRfcVectorsTest {

  @TableTest({
    "Scenario                                                | Grammar  | Route                       | Hit URL               | Present    | Tag                          ",
    "#1 Basics                                               | DEFAULT  | '/users'                    | '/users'              | {}         | '/users'                     ",
    "#2 Basics                                               | DEFAULT  | '/'                         | '/'                   | {}         | '/'                          ",
    "#3 Basics                                               | DEFAULT  | '/users/:id'                | '/users/42'           | {}         | '/users/{id}'                ",
    "#3 Basics                                               | LEGACY   | '/users/:id'                | '/users/42'           | {}         | '/users/{id}'                ",
    "#4 Basics                                               | DEFAULT  | '/users/:id/posts/:post_id' | '/users/1/posts/5'    | {}         | '/users/{id}/posts/{post_id}'",
    "#5 Basics                                               | DEFAULT  | '/users/:id/'               | '/users/1/'           | {}         | '/users/{id}/'               ",
    "#6 Static text in a segment                             | DEFAULT  | '/users/user-:id'           | '/users/user-42'      | {}         | '/users/user-{id}'           ",
    "#7 Static text in a segment                             | DEFAULT  | '/v:version/items'          | '/v2/items'           | {}         | '/v{version}/items'          ",
    "#8 Static text in a segment                             | DEFAULT  | '/files/:name.json'         | '/files/report.json'  | {}         | '/files/{name}.json'         ",
    "#9 Static text in a segment                             | DEFAULT  | '/caf%C3%A9'                | '/caf%C3%A9'          | {}         | '/caf%C3%A9'                 ",
    "#9 Static text in a segment (raw)                       | LEGACY   | '/café'                     | '/caf%C3%A9'          | {}         | '/caf%C3%A9'                 ",
    "#11 Static text in a segment                            | DEFAULT  | '/a+b/:id'                  | '/a+b/1'              | {}         | '/a%2Bb/{id}'                ",
    "#12 Several parameters in one segment                   | DEFAULT  | '/photos/:id.:format'       | '/photos/1.jpg'       | {}         | '/photos/{id}.{format}'      ",
    "#13 Several parameters in one segment                   | DEFAULT  | '/d/:a-:b-:c'               | '/d/1-2-3'            | {}         | '/d/{a}-{b}-{c}'             ",
    "#13 Several parameters in one segment                   | LEGACY   | '/d/:a-:b-:c'               | '/d/1-2-3'            | {}         | '/d/{a}-{b}-{c}'             ",
    "#14 Several parameters in one segment                   | DEFAULT  | '/v/:major:minor'           | '/v/12'               | {}         | '/v/{major}{minor}'          ",
    "#30 Parameter names                                     | EXTENDED | '/users/:user-id'           | '/users/1'            | {}         | '/users/{user_id}'           ",
    "#32 Parameter names, collision                          | EXTENDED | '/a/:user-id/:user_id'      | '/a/1/2'              | {}         | '/a/{user_id_2}/{user_id}'   ",
    "#33 Parameter names, collision (suffix _2 already used) | EXTENDED | '/a/:x-y/:x_y/:x_y_2'       | '/a/1/2/3'            | {}         | '/a/{x_y_3}/{x_y}/{x_y_2}'   ",
    "#36 Catch-all, unnamed                                  | DEFAULT  | '/static/*'                 | '/static/css/app.css' | {anon}     | '/static/{param1}'           ",
    "#37 Catch-all, unnamed, name already used               | DEFAULT  | '/w/:param1/*'              | '/w/x/a/b'            | {anon}     | '/w/{param1}/{param2}'       ",
    "#39 Catch-all, absent (unnamed)                         | DEFAULT  | '/files/*'                  | '/files'              | {}         | '/files'                     ",
    "#49 Catch-all, empty value (unnamed)                    | DEFAULT  | '/files/*'                  | '/files/'             | {'anon=/'} | '/files/'                    "
  })
  void rendersRfcRows(
      Grammar grammar, String route, String hitUrl, Set<String> present, String tag) {
    assertEquals(
        tag,
        VertxRouteParser.parse(route, grammar).render(null, RfcRouteNotation.presence(present)),
        hitUrl);
  }
}
