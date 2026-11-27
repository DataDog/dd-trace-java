package datadog.trace.instrumentation.springwebflux.server;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.RequestPath;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.tabletest.junit.TableTest;

/**
 * Prefix, presence and no-tag rules of {@link WebfluxNormalizedRoute}, with real {@link
 * RequestPath} and {@link PathPattern} instances, so that Spring's own matching decides like at
 * runtime, and interface proxies for the span and the functional {@link ServerRequest}. The
 * annotated entry point is called with the exchange values: a fake {@code ServerWebExchange} would
 * need spring-context, absent from this classpath. API Security is enabled by default.
 */
class WebfluxNormalizedRouteTest {

  private boolean appSecActiveBefore;

  @BeforeEach
  void forceAppSecActive() {
    appSecActiveBefore = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
  }

  @AfterEach
  void restoreAppSecActive() {
    ActiveSubsystems.APPSEC_ACTIVE = appSecActiveBefore;
  }

  @TableTest({
    "Scenario                                     | Context Path | Raw Path            | Pattern            | Vars           | Full Path | Normalized             ",
    "no context path                              | ''           | '/users/1'          | '/users/{id}'      |                | false     | '/users/{id}'          ",
    "no context path, full path mode              | ''           | '/users/1'          | '/users/{id}'      |                | true      | '/users/{id}'          ",
    "context path is the prefix                   | '/base'      | '/base/users/1'     | '/users/{id}'      |                | false     | '/base/users/{id}'     ",
    "escaped context path is decoded then encoded | '/my%20ctx'  | '/my%20ctx/users/1' | '/users/{id}'      |                | false     | '/my%20ctx/users/{id}' ",
    "double star on the application path          | '/base'      | '/base/static/a/b'  | '/static/**'       |                | false     | '/base/static/{param1}'",
    "double star matched nothing                  | '/base'      | '/base/static'      | '/static/**'       |                | false     | '/base/static'         ",
    "double star matched a slash                  | '/base'      | '/base/static/'     | '/static/**'       |                | false     | '/base/static/'        ",
    "capture rest bound                           | '/base'      | '/base/rest/a/b'    | '/rest/{*rest}'    | [rest: '/a/b'] | false     | '/base/rest/{rest}'    ",
    "capture rest bound to nothing                | '/base'      | '/base/rest'        | '/rest/{*rest}'    | [rest: '']     | false     | '/base/rest'           ",
    "5.2 full path pattern: no prefix added       | '/base'      | '/base/fn/a/b'      | '/base/fn/{*rest}' | [rest: '/a/b'] | true      | '/base/fn/{rest}'      ",
    "5.2 full path pattern, static                | '/base'      | '/base/hello'       | '/base/hello'      |                | true      | '/base/hello'          ",
    "5.2 full path double star                    | '/base'      | '/base/fns/a/b'     | '/base/fns/**'     |                | true      | '/base/fns/{param1}'   ",
    "5.2 full path double star matched nothing    | '/base'      | '/base/fns'         | '/base/fns/**'     |                | true      | '/base/fns'            ",
    "5.2 full path double star matched a slash    | '/base'      | '/base/fns/'        | '/base/fns/**'     |                | true      | '/base/fns/'           ",
    "5.2 full path, pattern matches both paths    | '/base'      | '/base/base/x'      | '/base/**'         |                | true      | '/base/{param1}'       ",
    "5.3 app relative, pattern matches both paths | '/base'      | '/base/base/x'      | '/base/**'         |                | false     | '/base/base/{param1}'  ",
    "5.2 annotated pattern repeating the context  | '/base'      | '/base/base/x'      | '/base/{id}'       | [id: x]        | false     | '/base/base/{id}'      "
  })
  void annotated(
      String contextPath,
      String rawPath,
      String pattern,
      Map<String, String> vars,
      boolean fullPath,
      String normalized) {
    Map<String, Object> tags = routeTags(pattern);

    WebfluxNormalizedRoute.tagAnnotated(
        span(tags),
        RequestPath.parse(URI.create(rawPath), contextPath),
        vars,
        parse(pattern),
        fullPath);

    assertEquals(normalized, tags.get(NORMALIZED_ROUTE_TAG));
  }

  @Test
  void annotatedControllerIsNeverInFullPathMode() {
    // a HandlerMethod (or any non HandlerFunction handler) matches the path within the application
    assertFalse(WebfluxNormalizedRoute.patternOnFullPath(new Object()));
    assertFalse(WebfluxNormalizedRoute.patternOnFullPath(null));
  }

  @Test
  void functionalHandlerFollowsTheSpringVersion() {
    HandlerFunction<?> handler = request -> null;

    assertEquals(
        WebfluxNormalizedRoute.functionalPredicatesMatchFullPath(ServerRequest.class),
        WebfluxNormalizedRoute.patternOnFullPath(handler));
  }

  @Test
  void functionalPredicatesMatchFullPathBefore53() {
    assertTrue(WebfluxNormalizedRoute.functionalPredicatesMatchFullPath(Request52.class));
    assertFalse(WebfluxNormalizedRoute.functionalPredicatesMatchFullPath(Request53.class));
  }

  /** {@code ServerRequest} shape before 5.3: no {@code requestPath()}. */
  interface Request52 {
    String path();
  }

  /** {@code ServerRequest} shape from 5.3: {@code requestPath()} is present. */
  interface Request53 extends Request52 {
    RequestPath requestPath();
  }

  @Test
  void annotatedNoTagWhenAppSecIsInactive() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    Map<String, Object> tags = routeTags("/users/{id}");

    WebfluxNormalizedRoute.tagAnnotated(
        span(tags),
        RequestPath.parse(URI.create("/users/1"), ""),
        null,
        parse("/users/{id}"),
        false);

    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }

  @TableTest({
    "Scenario                                  | Uri             | Matched Path | Route           | Vars       | Normalized          ",
    "raw path ends with the matched path       | '/base/greet/x' | '/greet/x'   | '/greet/{name}' | [name: x]  | '/base/greet/{name}'",
    "escaped prefix is decoded then encoded    | '/my%20ctx/a'   | '/a'         | '/a'            |            | '/my%20ctx/a'       ",
    "matched path not on a segment boundary    | '/api/greet/x'  | 'greet/x'    | '/greet/{name}' | [name: x]  | '/greet/{name}'     ",
    "matched path is the whole raw path        | '/greet/x'      | '/greet/x'   | '/greet/{name}' | [name: x]  | '/greet/{name}'     ",
    "matched path is not a suffix              | '/base/greet/x' | '/other/x'   | '/greet/{name}' | [name: x]  | '/greet/{name}'     ",
    "empty matched path: whole raw path        | '/base'         | ''           | '/{*rest}'      | [rest: ''] | '/base'             ",
    "no uri                                    |                 | '/greet/x'   | '/greet/{name}' | [name: x]  | '/greet/{name}'     ",
    "no matched path                           | '/base/greet/x' |              | '/greet/{name}' | [name: x]  | '/greet/{name}'     ",
    "double star remainder on the matched path | '/base/fns/a/b' | '/fns/a/b'   | '/fns/**'       |            | '/base/fns/{param1}'",
    "double star matched a slash               | '/base/fns/'    | '/fns/'      | '/fns/**'       |            | '/base/fns/'        ",
    "capture rest bound to nothing             | '/base/fn'      | '/fn'        | '/fn/{*rest}'   | [rest: ''] | '/base/fn'          "
  })
  void functional(
      String uri, String matchedPath, String route, Map<String, String> vars, String normalized) {
    Map<String, Object> tags = routeTags(route);

    WebfluxNormalizedRoute.tagFunctional(
        span(tags), serverRequest(uri == null ? null : URI.create(uri), matchedPath, vars), route);

    assertEquals(normalized, tags.get(NORMALIZED_ROUTE_TAG));
  }

  @TableTest({
    "Scenario            | Route                 ",
    "no route            |                       ",
    "not a path          | 'GET'                 ",
    "compound predicate  | '/a /b'               ",
    "header predicate    | '/a Accept: text/html'",
    "unbalanced braces   | '/a/{id'              ",
    "empty variable name | '/a/{}'               "
  })
  void functionalNoTag(String route) {
    Map<String, Object> tags = routeTags(route == null ? "/a" : route);

    WebfluxNormalizedRoute.tagFunctional(
        span(tags), serverRequest(URI.create("/base/a"), "/a", Collections.emptyMap()), route);

    assertNull(tags.get(NORMALIZED_ROUTE_TAG));
  }

  private static PathPattern parse(String pattern) {
    return new PathPatternParser().parse(pattern);
  }

  /** Tags of a server span that already has {@code http.route}, as the helper requires. */
  private static Map<String, Object> routeTags(String route) {
    Map<String, Object> tags = new HashMap<>();
    tags.put(HTTP_ROUTE, route);
    return tags;
  }

  /** Span whose tags live in {@code tags}; other methods return defaults. */
  private static AgentSpan span(Map<String, Object> tags) {
    return (AgentSpan)
        Proxy.newProxyInstance(
            WebfluxNormalizedRouteTest.class.getClassLoader(),
            new Class<?>[] {AgentSpan.class},
            (proxy, method, args) -> {
              if (args != null && args.length > 0) {
                // tags set by KnownTags id are kept under their name
                final Object key =
                    args[0] instanceof Long ? KnownTagCodec.nameOf((Long) args[0]) : args[0];
                if (key instanceof String) {
                  if (method.getName().equals("getTag") && args.length == 1) {
                    return tags.get(key);
                  }
                  if (method.getName().equals("setTag") && args.length == 2) {
                    tags.put((String) key, args[1]);
                    return proxy;
                  }
                }
              }
              return defaultValue(method, proxy, args);
            });
  }

  private static ServerRequest serverRequest(URI uri, String path, Map<String, String> vars) {
    Map<String, Object> answers = new HashMap<>();
    answers.put("uri", uri);
    answers.put("path", path);
    answers.put("pathVariables", vars);
    return fake(ServerRequest.class, answers);
  }

  /** Proxy answering by method name from {@code answers}, defaults otherwise. */
  private static <T> T fake(Class<T> type, Map<String, Object> answers) {
    return type.cast(
        Proxy.newProxyInstance(
            WebfluxNormalizedRouteTest.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) ->
                answers.containsKey(method.getName())
                    ? answers.get(method.getName())
                    : defaultValue(method, proxy, args)));
  }

  /** Answers {@link Object} methods by identity; other unexpected calls get a neutral value. */
  private static Object defaultValue(Method method, Object proxy, Object[] args) {
    switch (method.getName()) {
      case "equals":
        return args != null && args.length == 1 && proxy == args[0];
      case "hashCode":
        return System.identityHashCode(proxy);
      case "toString":
        return "fake " + method.getDeclaringClass().getSimpleName();
      default:
        break;
    }
    Class<?> returnType = method.getReturnType();
    if (returnType.isInstance(proxy)) {
      return proxy;
    }
    if (returnType == boolean.class) {
      return false;
    }
    if (returnType == long.class) {
      return 0L;
    }
    if (returnType == int.class) {
      return 0;
    }
    if (returnType.isPrimitive() && returnType != void.class) {
      throw new UnsupportedOperationException(method.toString());
    }
    return null;
  }
}
