package datadog.trace.api.appsec.route;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.tabletest.junit.TableTest;

/**
 * The RFC-1125 "Test cases" rows (see {@link NormalizedRouteRfcVectorsTest}) that JAX-RS
 * {@code @Path} can express, written in JAX-RS syntax and run through {@link JaxRsRouteParser}.
 * Presence comes from the value the framework binds ({@code Name} / {@code Value}; a blank name
 * means nothing bound), through {@link BoundValuesPresenceResolver}, as the JAX-RS decorators do.
 *
 * <p>Not applicable: 15-29 and 39-45 (optional parameters, optional static text and optional groups
 * do not exist in {@code @Path}), 36-38 and 50 (unnamed wildcards: every JAX-RS parameter has a
 * name), 46-48 (Go only).
 *
 * <p>Row 49: for {@code /files/{path: .*}} Jersey binds {@code ""} on {@code /files/} (the regex
 * runs after {@code /files/}), which the core treats as absent ({@code /files}); it binds {@code /}
 * on {@code /files//}, which gives the RFC tag {@code /files/}. Both bindings are listed.
 */
class JaxRsRouteParserRfcVectorsTest {

  @TableTest({
    "Scenario                                                | Route                         | Hit URL              | Name | Value       | Tag                          ",
    "#1 Basics                                               | '/users'                      | '/users'             |      |             | '/users'                     ",
    "#2 Basics                                               | '/'                           | '/'                  |      |             | '/'                          ",
    "#3 Basics                                               | '/users/{id}'                 | '/users/42'          | id   | '42'        | '/users/{id}'                ",
    "#4 Basics                                               | '/users/{id}/posts/{post_id}' | '/users/1/posts/5'   | id   | '1'         | '/users/{id}/posts/{post_id}'",
    "#5 Basics                                               | '/users/{id}/'                | '/users/1/'          | id   | '1'         | '/users/{id}/'               ",
    "#6 Static text in a segment                             | '/users/user-{id}'            | '/users/user-42'     | id   | '42'        | '/users/user-{id}'           ",
    "#7 Static text in a segment                             | '/v{version}/items'           | '/v2/items'          |      |             | '/v{version}/items'          ",
    "#8 Static text in a segment                             | '/files/{name}.json'          | '/files/report.json' |      |             | '/files/{name}.json'         ",
    "#9 Static text in a segment                             | '/café'                       | '/caf%C3%A9'         |      |             | '/caf%C3%A9'                 ",
    "#10 Static text in a segment                            | '/user:me'                    | '/user:me'           |      |             | '/user%3Ame'                 ",
    "#11 Static text in a segment                            | '/a+b/{id}'                   | '/a+b/1'             |      |             | '/a%2Bb/{id}'                ",
    "#12 Several parameters in one segment                   | '/photos/{id}.{format}'       | '/photos/1.jpg'      |      |             | '/photos/{id}.{format}'      ",
    "#13 Several parameters in one segment                   | '/d/{a}-{b}-{c}'              | '/d/1-2-3'           |      |             | '/d/{a}-{b}-{c}'             ",
    "#14 Several parameters in one segment                   | '/v/{major}{minor}'           | '/v/12'              |      |             | '/v/{major}{minor}'          ",
    "#30 Parameter names                                     | '/users/{user-id}'            | '/users/1'           |      |             | '/users/{user_id}'           ",
    "#31 Parameter names                                     | '/items/{item.id}'            | '/items/1'           |      |             | '/items/{item_id}'           ",
    "#32 Parameter names, collision                          | '/a/{user-id}/{user_id}'      | '/a/1/2'             |      |             | '/a/{user_id_2}/{user_id}'   ",
    "#33 Parameter names, collision (suffix _2 already used) | '/a/{x-y}/{x_y}/{x_y_2}'      | '/a/1/2/3'           |      |             | '/a/{x_y_3}/{x_y}/{x_y_2}'   ",
    "#34 Parameter names, non-ASCII                          | '/u/{föo}'                    | '/u/1'               |      |             | '/u/{f_o}'                   ",
    "#35 Catch-all, named                                    | '/files/{path: .*}'           | '/files/a/b/c.txt'   | path | 'a/b/c.txt' | '/files/{path}'              ",
    "#49 Catch-all, empty value (Jersey binds slash)         | '/files/{path: .*}'           | '/files//'           | path | '/'         | '/files/'                    ",
    "#49 Catch-all, empty value (Jersey binds empty)         | '/files/{path: .*}'           | '/files/'            | path | ''          | '/files'                     "
  })
  void rendersRfcRows(String route, String hitUrl, String name, String value, String tag) {
    RouteTemplate template = JaxRsRouteParser.parse(route);
    Map<String, String> bound = name == null ? null : singletonMap(name, value);
    assertEquals(
        tag, template.render(null, BoundValuesPresenceResolver.of(template, bound, null)), hitUrl);
  }
}
