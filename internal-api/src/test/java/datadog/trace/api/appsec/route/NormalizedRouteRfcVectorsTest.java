package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.tabletest.junit.TableTest;

/**
 * Conformance with the RFC-1125 "Test cases" table (version of 2026-11-16, 50 rows), one row per
 * RFC row and in the same order, so that a change in the RFC can be diffed against this file.
 *
 * <p>{@code Present} lists what the hit URL binds: parameter names, {@code anon} for an unnamed
 * catch-all, or the text of an optional static group; {@code <key>=/} means a catch-all that
 * matched only {@code /} (row 49). An empty cell means presence cannot be determined (fallback,
 * rows 28-29).
 */
class NormalizedRouteRfcVectorsTest {

  // Rows 46-48 are not applicable to Java and are not listed:
  //   46 gin ':name.json' parameter syntax (Go only)
  //   47-48 ServeMux prefix route '/static/' with an implicit catch-all (Go only)
  // Row 24-25 route is '/posts/{page?}' with default 1: the default is never bound from the URL.
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
    "#15 Optional, present                                   | '/users/{id}/{tab?}'          | '/users/1/posts'      | {tab}      | '/users/{id}/{tab}'          ",
    "#16 Optional, absent                                    | '/users/{id}/{tab?}'          | '/users/1'            | {}         | '/users/{id}'                ",
    "#17 Optional, present                                   | '/files/{name}.{ext?}'        | '/files/a.txt'        | {ext}      | '/files/{name}.{ext}'        ",
    "#18 Optional, absent                                    | '/files/{name}.{ext?}'        | '/files/a'            | {}         | '/files/{name}'              ",
    "#19 Optional, absent                                    | '/search.{format?}'           | '/search'             | {}         | '/search'                    ",
    "#20 Optional, present                                   | '/search.{format?}'           | '/search.json'        | {format}   | '/search.{format}'           ",
    "#21 Optional, absent                                    | '/a/{x?}/{y?}'                | '/a'                  | {}         | '/a'                         ",
    "#22 Optional, present                                   | '/a/{x?}/{y?}'                | '/a/1'                | {x}        | '/a/{x}'                     ",
    "#23 Optional, present                                   | '/a/{x?}/{y?}'                | '/a/1/2'              | {x, y}     | '/a/{x}/{y}'                 ",
    "#24 Optional, absent (default not counted)              | '/posts/{page?}'              | '/posts'              | {}         | '/posts'                     ",
    "#25 Optional, present                                   | '/posts/{page?}'              | '/posts/1'            | {page}     | '/posts/{page}'              ",
    "#26 Optional static text, present                       | '/drafts[/preview]'           | '/drafts/preview'     | {preview}  | '/drafts/preview'            ",
    "#27 Optional static text, absent                        | '/drafts[/preview]'           | '/drafts'             | {}         | '/drafts'                    ",
    "#28 Optional, cannot be determined (fallback)           | '/users/{id}/{tab?}'          | '/users/1/posts'      |            | '/users/{id}'                ",
    "#29 Optional, cannot be determined (fallback)           | '/files/{name}.{ext?}'        | '/files/a.txt'        |            | '/files/{name}'              ",
    "#30 Parameter names                                     | '/users/{user-id}'            | '/users/1'            | {}         | '/users/{user_id}'           ",
    "#31 Parameter names                                     | '/items/{item.id}'            | '/items/1'            | {}         | '/items/{item_id}'           ",
    "#32 Parameter names, collision                          | '/a/{user-id}/{user_id}'      | '/a/1/2'              | {}         | '/a/{user_id_2}/{user_id}'   ",
    "#33 Parameter names, collision (suffix _2 already used) | '/a/{x-y}/{x_y}/{x_y_2}'      | '/a/1/2/3'            | {}         | '/a/{x_y_3}/{x_y}/{x_y_2}'   ",
    "#34 Parameter names, non-ASCII                          | '/u/{föo}'                    | '/u/1'                | {}         | '/u/{f_o}'                   ",
    "#35 Catch-all, named                                    | '/files/{*path}'              | '/files/a/b/c.txt'    | {path}     | '/files/{path}'              ",
    "#36 Catch-all, unnamed                                  | '/static/**'                  | '/static/css/app.css' | {anon}     | '/static/{param1}'           ",
    "#37 Catch-all, unnamed, name already used               | '/w/{param1}/**'              | '/w/x/a/b'            | {anon}     | '/w/{param1}/{param2}'       ",
    "#38 Catch-all, mid-path                                 | '/a/**/b'                     | '/a/x/y/b'            | {anon}     | '/a/{param1}/b'              ",
    "#39 Catch-all, optional, absent                         | '/files/{*path?}'             | '/files'              | {}         | '/files'                     ",
    "#40 Optional group with static text, present            | '/posts/{id}(.{format})'      | '/posts/1.json'       | {format}   | '/posts/{id}.{format}'       ",
    "#41 Optional group with static text, absent             | '/posts/{id}(.{format})'      | '/posts/1'            | {}         | '/posts/{id}'                ",
    "#42 Optional group after a static segment, present      | '/items(.{format})'           | '/items.json'         | {format}   | '/items.{format}'            ",
    "#43 Optional group after a static segment, absent       | '/items(.{format})'           | '/items'              | {}         | '/items'                     ",
    "#44 Optional group with more static text, present       | '/posts/{id}(/page-{n})'      | '/posts/1/page-2'     | {n}        | '/posts/{id}/page-{n}'       ",
    "#45 Optional group with more static text, absent        | '/posts/{id}(/page-{n})'      | '/posts/1'            | {}         | '/posts/{id}'                ",
    "#49 Catch-all, empty value                              | '/files/{*path}'              | '/files/'             | {'path=/'} | '/files/'                    ",
    "#50 Wildcard in a segment, unnamed                      | '/files/*.txt'                | '/files/report.txt'   | {}         | '/files/{param1}.txt'        "
  })
  void rendersRfcRows(String route, String hitUrl, Set<String> present, String tag) {
    assertEquals(
        tag,
        RfcRouteNotation.parse(route).render(null, RfcRouteNotation.presence(present)),
        hitUrl);
  }
}
