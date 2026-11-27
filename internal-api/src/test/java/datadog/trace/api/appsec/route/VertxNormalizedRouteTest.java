package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.appsec.route.VertxRouteParser.Grammar;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class VertxNormalizedRouteTest {

  @TableTest({
    "Scenario                                  | Declared                    | Grammar  | Mount         | Path                       | Params                  | Normalized                ",
    "static route                              | '/users'                    | DEFAULT  |               | '/users'                   | [:]                     | '/users'                  ",
    "static route, mount with slash            | '/users'                    | DEFAULT  | '/sub/'       | '/sub/users'               | [:]                     | '/sub/users'              ",
    "static route, mount without slash         | '/users'                    | LEGACY   | '/sub'        | '/sub/users'               | [:]                     | '/sub/users'              ",
    "root route                                | '/'                         | DEFAULT  |               | '/'                        | [:]                     | '/'                       ",
    "root route under a mount                  | '/'                         | DEFAULT  | '/sub/'       | '/sub/'                    | [:]                     | '/sub/'                   ",
    "multi params per segment                  | '/a/:id.:format'            | DEFAULT  |               | '/a/1.json'                | [id: '1', format: json] | '/a/{id}.{format}'        ",
    "dashed params, extended grammar           | '/t/:a-b'                   | EXTENDED |               | '/t/1'                     | [a-b: '1']              | '/t/{a_b}'                ",
    "encoded literal                           | '/caf%C3%A9'                | DEFAULT  |               | '/caf%C3%A9'               | [:]                     | '/caf%C3%A9'              ",
    "encoded mount                             | '/x'                        | DEFAULT  | '/caf%C3%A9/' | '/caf%C3%A9/x'             | [:]                     | '/caf%C3%A9/x'            ",
    "catch-all, base only                      | '/catch-all/*'              | DEFAULT  |               | '/catch-all'               | [:]                     | '/catch-all'              ",
    "catch-all, trailing slash                 | '/catch-all/*'              | DEFAULT  |               | '/catch-all/'              | [:]                     | '/catch-all/'             ",
    "catch-all, present                        | '/catch-all/*'              | DEFAULT  |               | '/catch-all/a/b'           | [:]                     | '/catch-all/{param1}'     ",
    "catch-all, nested base, trailing slash    | '/api_security/catch-all/*' | DEFAULT  |               | '/api_security/catch-all/' | [:]                     | '/api_security/catch-all/'",
    "catch-all, other segment                  | '/catch-all/*'              | DEFAULT  |               | '/catch-allx'              | [:]                     |                           ",
    "catch-all, path outside the base          | '/catch-all/*'              | DEFAULT  |               | '/other/a'                 | [:]                     | '/catch-all'              ",
    "catch-all, no normalized path             | '/catch-all/*'              | DEFAULT  |               |                            | [:]                     | '/catch-all'              ",
    "catch-all under mount with slash          | '/catch-all/*'              | DEFAULT  | '/sub/'       | '/sub/catch-all/a'         | ['*': 'a']              | '/sub/catch-all/{param1}' ",
    "catch-all under mount without slash       | '/catch-all/*'              | LEGACY   | '/sub'        | '/sub/catch-all/a'         | [:]                     | '/sub/catch-all/{param1}' ",
    "catch-all under mount, base only          | '/catch-all/*'              | DEFAULT  | '/sub/'       | '/sub/catch-all'           | [:]                     | '/sub/catch-all'          ",
    "catch-all, path outside the mount         | '/catch-all/*'              | DEFAULT  | '/sub/'       | '/other/catch-all/a'       | [:]                     | '/sub/catch-all'          ",
    "root catch-all, root path                 | '/*'                        | DEFAULT  |               | '/'                        | [:]                     | '/'                       ",
    "root catch-all, present                   | '/*'                        | DEFAULT  |               | '/x'                       | [:]                     | '/{param1}'               ",
    "root catch-all under mount, present       | '/*'                        | DEFAULT  | '/sub/'       | '/sub/x'                   | [:]                     | '/sub/{param1}'           ",
    "root catch-all under mount, slash         | '/*'                        | DEFAULT  | '/sub/'       | '/sub/'                    | [:]                     | '/sub/'                   ",
    "root catch-all under mount, base only     | '/*'                        | LEGACY   | '/sub'        | '/sub'                     | [:]                     | '/sub'                    ",
    "root catch-all, bare mount, slash         | '/*'                        | LEGACY   | '/sub'        | '/sub/'                    | [:]                     | '/sub/'                   ",
    "root catch-all, bare mount, present       | '/*'                        | LEGACY   | '/sub'        | '/sub/x'                   | [:]                     | '/sub/{param1}'           ",
    "root catch-all, bare mount, base only 4.x | '/*'                        | DEFAULT  | '/sub'        | '/sub'                     | [:]                     | '/sub'                    ",
    "root catch-all, bare mount, other segment | '/*'                        | LEGACY   | '/sub'        | '/subway/x'                | [:]                     |                           ",
    "root catch-all, bare mount, other seg 4.x | '/*'                        | DEFAULT  | '/sub'        | '/subway'                  | [:]                     |                           ",
    "catch-all under mount, other segment      | '/x/*'                      | DEFAULT  | '/sub'        | '/sub/xy'                  | [:]                     |                           ",
    "in-segment catch-all, present             | '/img*'                     | DEFAULT  |               | '/img42'                   | [:]                     | '/img{param1}'            ",
    "in-segment catch-all, absent              | '/img*'                     | DEFAULT  |               | '/img'                     | [:]                     | '/img'                    ",
    "param prefix route, slash                 | '/users/:id/*'              | DEFAULT  |               | '/users/5/'                | [id: '5']               | '/users/{id}/'            ",
    "param prefix route, present               | '/users/:id/*'              | DEFAULT  |               | '/users/5/a/b'             | [id: '5']               | '/users/{id}/{param1}'    ",
    "param prefix route, absent                | '/users/:id/*'              | DEFAULT  |               | '/users/5'                 | [id: '5']               | '/users/{id}'             ",
    "param prefix route, too few segments      | '/users/:id/*'              | DEFAULT  |               | '/users'                   | [:]                     | '/users/{id}'             ",
    "param prefix route, no leading slash      | '/users/:id/*'              | DEFAULT  |               | 'users/5/a'                | [:]                     | '/users/{id}'             ",
    "param prefix route under mount            | '/users/:id/*'              | DEFAULT  | '/sub/'       | '/sub/users/5/a'           | [id: '5']               | '/sub/users/{id}/{param1}'",
    "param prefix route outside the mount      | '/users/:id/*'              | DEFAULT  | '/sub/'       | '/users/5/a'               | [id: '5']               | '/sub/users/{id}'         ",
    "in-segment star after param, present      | '/u/:id*'                   | DEFAULT  |               | '/u/5/x'                   | [id: '5']               | '/u/{id}{param1}'         ",
    "in-segment star after param, absent       | '/u/:id*'                   | DEFAULT  |               | '/u/5'                     | [id: '5']               | '/u/{id}'                 ",
    "in-segment star after literal, present    | '/f/:id.json*'              | DEFAULT  |               | '/f/a.json.jsonx'          | [id: a.json]            | '/f/{id}.json{param1}'    ",
    "in-segment star after literal, absent     | '/f/:id.json*'              | DEFAULT  |               | '/f/a.json'                | [id: a]                 | '/f/{id}.json'            ",
    "in-segment star after literal, no match   | '/f/:id.json*'              | DEFAULT  |               | '/f/.json'                 | [:]                     | '/f/{id}.json'            ",
    "in-segment star, literal segment, present | '/u/:id/img*'               | DEFAULT  |               | '/u/5/img1'                | [id: '5']               | '/u/{id}/img{param1}'     ",
    "in-segment star, literal segment, absent  | '/u/:id/img*'               | DEFAULT  |               | '/u/5/img'                 | [id: '5']               | '/u/{id}/img'             ",
    "in-segment star, literal segment mismatch | '/u/:id/img*'               | DEFAULT  |               | '/u/5/pic1'                | [id: '5']               | '/u/{id}/img'             ",
    "in-segment star, legacy colon literal     | '/u/:id/a:*'                | LEGACY   |               | '/u/5/a:x'                 | [id: '5']               | '/u/{id}/a%3A{param1}'    ",
    "not expressible regex metachar            | '/a/:id?'                   | DEFAULT  |               | '/a/1'                     | [:]                     |                           ",
    "null declared path                        |                             | DEFAULT  |               | '/a'                       | [:]                     |                           ",
    "null grammar                              | '/a'                        |          |               | '/a'                       | [:]                     |                           ",
    "param mount: undeclared bound param       | '/users/:id'                | DEFAULT  | '/acme/api/'  | '/acme/api/users/1'        | [tenant: acme, id: '1'] |                           ",
    "same params without mount                 | '/users/:id'                | DEFAULT  |               | '/users/1'                 | [tenant: acme, id: '1'] | '/users/{id}'             ",
    "same params with empty mount              | '/users/:id'                | DEFAULT  | ''            | '/users/1'                 | [tenant: acme, id: '1'] | '/users/{id}'             ",
    "same params with root mount               | '/users/:id'                | DEFAULT  | '/'           | '/users/1'                 | [tenant: acme, id: '1'] | '/users/{id}'             ",
    "mount: rest param ignored                 | '/users/:id'                | DEFAULT  | '/sub/'       | '/sub/users/1'             | [id: '1', '*': x]       | '/sub/users/{id}'         ",
    "mount: no params                          | '/users/:id'                | DEFAULT  | '/sub/'       | '/sub/users/1'             |                         | '/sub/users/{id}'         ",
    "mount: encoded slash                      | '/users/:id'                | DEFAULT  | '/a%2Fb/'     | '/a%2Fb/users/1'           |                         |                           ",
    "mount: encoded slash, lower case          | '/users/:id'                | DEFAULT  | '/a%2fb/'     | '/a%2fb/users/1'           |                         |                           ",
    "mount: other escape kept                  | '/users/:id'                | DEFAULT  | '/a%20b/'     | '/a%20b/users/1'           |                         | '/a%20b/users/{id}'       ",
    "mount: unreserved escape                  | '/users/:id'                | DEFAULT  | '/a%41b/'     | '/aAb/users/1'             |                         | '/aAb/users/{id}'         ",
    "mount: trailing percent                   | '/users/:id'                | DEFAULT  | '/a%2'        | '/a%2/users/1'             |                         | '/a%252/users/{id}'       "
  })
  void rendersTheNormalizedRoute(
      String declared,
      Grammar grammar,
      String mount,
      String path,
      Map<String, String> params,
      String normalized) {
    assertEquals(normalized, VertxNormalizedRoute.render(declared, grammar, mount, path, params));
  }

  @Test
  void clearsTheTagEvenWhenDisabled() {
    AgentSpan span = mock(AgentSpan.class);

    VertxNormalizedRoute.tag(span, "/users/:id", Grammar.DEFAULT, null, "/users/1", emptyMap());

    // the tag is only set when NormalizedRouteHelper is enabled (see the forked test)
    verify(span).setTag(NORMALIZED_ROUTE_TAG, (String) null);
  }

  @Test
  void neverThrows() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.setTag(anyString(), (String) isNull())).thenThrow(new IllegalStateException("boom"));

    assertDoesNotThrow(() ->
        VertxNormalizedRoute.tag(
            span, "/users/:id", Grammar.DEFAULT, null, "/users/1", emptyMap()));
    assertDoesNotThrow(() ->
        VertxNormalizedRoute.tag(
            null, "/users/:id", Grammar.DEFAULT, null, "/users/1", emptyMap()));
  }

  @Test
  void catchAllResolverOnlyAnswersForTheUnnamedCatchAll() {
    PresenceResolver resolver = VertxNormalizedRoute.catchAllResolver(RoutePresence.PRESENT);

    assertEquals(RoutePresence.PRESENT, resolver.presenceOf(RoutePart.catchAll(null)));
    assertEquals(RoutePresence.UNKNOWN, resolver.presenceOf(RoutePart.catchAll("rest")));
    assertEquals(RoutePresence.UNKNOWN, resolver.presenceOf(RoutePart.optionalParam("id")));
    assertEquals(
        RoutePresence.UNKNOWN, resolver.presenceOf(RoutePart.group(RoutePart.literal("a"))));
  }

  @Test
  void vertx4GrammarDefaultsToDefault() {
    assertEquals(Grammar.DEFAULT, VertxNormalizedRoute.vertx4Grammar());
    assertEquals(Grammar.DEFAULT, VertxNormalizedRoute.vertx4Grammar(false));
    assertEquals(Grammar.EXTENDED, VertxNormalizedRoute.vertx4Grammar(true));
  }
}
