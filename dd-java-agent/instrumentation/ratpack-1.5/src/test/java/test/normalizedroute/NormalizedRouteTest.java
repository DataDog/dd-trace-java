package test.normalizedroute;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.bootstrap.ActiveSubsystems;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;
import ratpack.test.embed.EmbeddedApp;

/**
 * Normalized route for Ratpack path bindings, asserted on both the {@code netty.request} and the
 * {@code ratpack.handler} span, with {@code http.route} unchanged.
 */
class NormalizedRouteTest extends AbstractNormalizedRouteTest {

  @TableTest({
    "Scenario                          | Path                                 | Route                                      | Normalized                                 ",
    "optional segment absent           | '/api_security/optional-segment/1'   | '/api_security/optional-segment/:id/:tab?' | '/api_security/optional-segment/{id}'      ",
    "optional segment present          | '/api_security/optional-segment/1/p' | '/api_security/optional-segment/:id/:tab?' | '/api_security/optional-segment/{id}/{tab}'",
    "optional chain both absent        | '/api_security/optional-chain'       | '/api_security/optional-chain/:x?/:y?'     | '/api_security/optional-chain'             ",
    "optional chain first present      | '/api_security/optional-chain/1'     | '/api_security/optional-chain/:x?/:y?'     | '/api_security/optional-chain/{x}'         ",
    "optional chain both present       | '/api_security/optional-chain/1/2'   | '/api_security/optional-chain/:x?/:y?'     | '/api_security/optional-chain/{x}/{y}'     ",
    "prefix catch-all with remainder   | '/api_security/catch-all/a/b/c.txt'  | '/api_security/catch-all'                  | '/api_security/catch-all/{param1}'         ",
    "prefix catch-all with a slash     | '/api_security/catch-all//'          | '/api_security/catch-all'                  | '/api_security/catch-all/'                 ",
    "prefix catch-all exact            | '/api_security/catch-all'            | '/api_security/catch-all'                  | '/api_security/catch-all'                  ",
    "encoded literal                   | '/api_security/encoded/caf%C3%A9'    | '/api_security/encoded/caf%C3%A9'          | '/api_security/encoded/caf%C3%A9'          ",
    "prefix and get without path       | '/users'                             | '/users/'                                  | '/users'                                   ",
    "prefix and get with token         | '/users/7'                           | '/users/:id'                               | '/users/{id}'                              ",
    "token in prefix and in inner path | '/t/acme/items/7'                    | '/t/:tenant/items/:id'                     | '/t/{tenant}/items/{id}'                   ",
    "regex token                       | '/regex/12'                          | '/regex/:id:\\d+'                          | '/regex/{id}'                              ",
    "unnamed regex token               | '/unnamed/12'                        | '/unnamed/::\\d+'                          | '/unnamed/{param1}'                        ",
    "unnamed regex then catch-all      | '/pfx/12/a'                          | '/pfx/::\\d+'                              | '/pfx/{param1}/{param2}'                   ",
    "token followed by text            | '/files/report.json'                 | '/files/:name.json'                        | '/files/{name}.json'                       ",
    "root get with empty path          | '/'                                  | '/'                                        | '/'                                        "
  })
  void normalizedRoute(String path, String route, String normalized) throws Exception {
    Map<String, Map<String, Object>> tags = spanTags(path);

    for (String span : new String[] {NETTY_SPAN, RATPACK_SPAN}) {
      assertEquals(route, String.valueOf(tags.get(span).get(HTTP_ROUTE)), span);
      assertEquals(normalized, String.valueOf(tags.get(span).get(NORMALIZED_ROUTE_TAG)), span);
    }
  }

  @Test
  void normalizedRouteForWrongMethodOnBoundRoute() throws Exception {
    Map<String, Map<String, Object>> tags = spanTags(app(), "POST", "/regex/12", 405);

    for (String span : new String[] {NETTY_SPAN, RATPACK_SPAN}) {
      assertEquals("/regex/:id:\\d+", String.valueOf(tags.get(span).get(HTTP_ROUTE)), span);
      assertEquals("/regex/{id}", String.valueOf(tags.get(span).get(NORMALIZED_ROUTE_TAG)), span);
    }
  }

  @TableTest({
    "Scenario                | Path               | Status | Route                   | Normalized               ",
    "handler exception       | '/err/acme/boom/7' | 500    | '/err/:tenant/boom/:id' | '/err/{tenant}/boom/{id}'",
    "client error from route | '/err/acme/bad/7'  | 400    | '/err/:tenant/bad/:id'  | '/err/{tenant}/bad/{id}' "
  })
  void normalizedRouteWhenBoundHandlerFails(
      String path, int status, String route, String normalized) throws Exception {
    Map<String, Map<String, Object>> tags = spanTags(path, status);

    for (String span : new String[] {NETTY_SPAN, RATPACK_SPAN}) {
      assertEquals(route, String.valueOf(tags.get(span).get(HTTP_ROUTE)), span);
      assertEquals(normalized, String.valueOf(tags.get(span).get(NORMALIZED_ROUTE_TAG)), span);
    }
  }

  @Test
  void noNormalizedRouteWhenPrefixFallsThroughToNotFound() throws Exception {
    Map<String, Map<String, Object>> tags = spanTags("/users/7/extra", 404);

    for (String span : new String[] {NETTY_SPAN, RATPACK_SPAN}) {
      assertNull(tags.get(span).get(NORMALIZED_ROUTE_TAG), span);
    }
  }

  @Test
  void noNormalizedRouteForOptionalTokenSharingThePrefixTokenName() throws Exception {
    Map<String, Map<String, Object>> tags = spanTags("/dup/1/2");

    for (String span : new String[] {NETTY_SPAN, RATPACK_SPAN}) {
      assertEquals("/dup/:id/:id?", String.valueOf(tags.get(span).get(HTTP_ROUTE)), span);
      assertNull(tags.get(span).get(NORMALIZED_ROUTE_TAG), span);
    }
  }

  @Test
  void noNormalizedRouteForRootAllHandler() throws Exception {
    try (EmbeddedApp rootAll =
        EmbeddedApp.fromHandlers(chain -> chain.all(ctx -> ctx.render("ok")))) {
      Map<String, Map<String, Object>> tags = spanTags(rootAll, "GET", "/anything", 200);

      for (String span : new String[] {NETTY_SPAN, RATPACK_SPAN}) {
        assertEquals("/", String.valueOf(tags.get(span).get(HTTP_ROUTE)), span);
        assertNull(tags.get(span).get(NORMALIZED_ROUTE_TAG), span);
      }
    }
  }

  @Test
  void noNormalizedRouteForOptionalTokenFollowedByText() throws Exception {
    Map<String, Map<String, Object>> tags = spanTags("/fmt/x.json");

    for (String span : new String[] {NETTY_SPAN, RATPACK_SPAN}) {
      assertEquals("/fmt/:f?.json", String.valueOf(tags.get(span).get(HTTP_ROUTE)), span);
      assertNull(tags.get(span).get(NORMALIZED_ROUTE_TAG), span);
    }
  }

  @Test
  void noNormalizedRouteWhenNoRouteMatches() throws Exception {
    Map<String, Map<String, Object>> tags = spanTags("/does-not-exist", 404);

    for (String span : new String[] {NETTY_SPAN, RATPACK_SPAN}) {
      assertNull(tags.get(span).get(NORMALIZED_ROUTE_TAG), span);
    }
  }

  @Test
  void noNormalizedRouteWhenAppSecIsInactive() throws Exception {
    ActiveSubsystems.APPSEC_ACTIVE = false;

    Map<String, Map<String, Object>> tags = spanTags("/regex/12");

    for (String span : new String[] {NETTY_SPAN, RATPACK_SPAN}) {
      assertEquals("/regex/:id:\\d+", String.valueOf(tags.get(span).get(HTTP_ROUTE)), span);
      assertNull(tags.get(span).get(NORMALIZED_ROUTE_TAG), span);
    }
  }
}
