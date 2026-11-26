package datadog.trace.instrumentation.springwebflux.server;

import datadog.trace.api.appsec.route.BoundValuesPresenceResolver;
import datadog.trace.api.appsec.route.NormalizedRouteHelper;
import datadog.trace.api.appsec.route.RouteTemplate;
import datadog.trace.api.appsec.route.SpringRouteParser;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.RequestPath;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Sets {@code _dd.appsec.normalized_route} next to {@code http.route} for WebFlux. Callers check
 * {@link NormalizedRouteHelper#isEnabled()} first. Never throws: any problem drops the tag.
 */
public final class WebfluxNormalizedRoute {

  private static final Logger log = LoggerFactory.getLogger(WebfluxNormalizedRoute.class);

  private WebfluxNormalizedRoute() {}

  /**
   * Routes with a matched {@link PathPattern}: annotated controllers, and functional routes on 5.2+
   * ({@code RouterFunctionMapping} sets the attribute too). The pattern is usually relative to the
   * application, so the prefix is {@link RequestPath#contextPath()} (includes {@code
   * spring.webflux.base-path}). Only functional routes on 5.2 have a pattern written against the
   * full path: see {@link #patternOnFullPath(Object)}.
   *
   * @param handler the handler given to {@code HandlerAdapter.handle(exchange, handler)}
   */
  public static void tagAnnotated(
      final AgentSpan span,
      final ServerWebExchange exchange,
      final PathPattern pattern,
      final Object handler) {
    try {
      tagAnnotated(
          span,
          exchange.getRequest().getPath(),
          exchange.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE),
          pattern,
          patternOnFullPath(handler));
    } catch (final Throwable t) {
      // never break the request: drop the tag
      log.debug("Failed to compute the normalized route", t);
    }
  }

  /**
   * {@link #tagAnnotated(AgentSpan, ServerWebExchange, PathPattern, Object)} on the exchange
   * values. {@code patternOnFullPath}: the pattern was matched against the full request path
   * (context path included), so no prefix is added.
   */
  static void tagAnnotated(
      final AgentSpan span,
      final RequestPath path,
      final Object uriTemplateVariables,
      final PathPattern pattern,
      final boolean patternOnFullPath) {
    final String patternString = pattern.getPatternString();
    final RouteTemplate template = SpringRouteParser.cached(patternString);
    if (!template.isExpressible()) {
      return;
    }
    final String contextPath = path.contextPath().value();
    final String prefix;
    final String matchedPath;
    if (patternOnFullPath || contextPath.isEmpty()) {
      // without a context path the full path is the path within the application
      prefix = "";
      matchedPath = path.value();
    } else {
      prefix = NormalizedRouteHelper.decodePath(contextPath);
      matchedPath = path.pathWithinApplication().value();
    }
    if (template.staticRoute() != null) {
      // nothing depends on the request: no presence to resolve
      NormalizedRouteHelper.tag(span, template, prefix, null);
      return;
    }
    NormalizedRouteHelper.tag(
        span,
        template,
        prefix,
        BoundValuesPresenceResolver.fromRaw(
            template,
            uriTemplateVariables,
            unnamedCatchAllRemainder(template, patternString, matchedPath),
            matchedPath.endsWith("/")));
  }

  /**
   * Functional routes: {@code route} is rebuilt from the predicate's {@code toString()}. It is
   * tagged only when it is a plain path pattern (starts with {@code /}, no whitespace left by
   * compound predicates such as {@code ||} or header predicates); otherwise the route cannot be
   * expressed and no tag is set. On 5.2+ {@link #tagAnnotated} runs afterwards with the matched
   * pattern and sets the tag again.
   *
   * <p>The predicates match {@link ServerRequest#path()}: {@code pathWithinApplication} on 5.3+,
   * the full path before (the route then already contains the context path). The prefix is the raw
   * request path minus that path, and the {@code **} remainder is computed on it.
   */
  public static void tagFunctional(
      final AgentSpan span, final ServerRequest request, final String route) {
    try {
      if (route == null || !route.startsWith("/") || hasWhitespace(route)) {
        return;
      }
      final RouteTemplate template = SpringRouteParser.cached(route);
      if (!template.isExpressible()) {
        return;
      }
      final String matchedPath = request.path();
      final String prefix = functionalPrefix(request, matchedPath);
      if (template.staticRoute() != null) {
        // nothing depends on the request: no presence to resolve
        NormalizedRouteHelper.tag(span, template, prefix, null);
        return;
      }
      NormalizedRouteHelper.tag(
          span,
          template,
          prefix,
          BoundValuesPresenceResolver.fromRaw(
              template,
              request.pathVariables(),
              unnamedCatchAllRemainder(template, route, matchedPath),
              matchedPath != null && matchedPath.endsWith("/")));
    } catch (final Throwable t) {
      // never break the request: drop the tag
      log.debug("Failed to compute the normalized route", t);
    }
  }

  /** Part matched by a trailing {@code **}, computed by Spring's own matcher; null if not used. */
  private static String unnamedCatchAllRemainder(
      final RouteTemplate template, final String pattern, final String matchedPath) {
    if (matchedPath == null || !template.needsUnnamedCatchAllRemainder()) {
      return null;
    }
    return PathMatcherHolder.PATH_MATCHER.extractPathWithinPattern(pattern, matchedPath);
  }

  /**
   * Decoded raw request path minus the path the predicates matched; "" when none or when the
   * matched path does not start on a segment boundary.
   */
  private static String functionalPrefix(final ServerRequest request, final String matchedPath) {
    if (matchedPath == null || (!matchedPath.isEmpty() && !matchedPath.startsWith("/"))) {
      return "";
    }
    final URI uri = request.uri();
    final String rawPath = uri == null ? null : uri.getRawPath();
    if (rawPath == null
        || rawPath.length() <= matchedPath.length()
        || !rawPath.endsWith(matchedPath)) {
      return "";
    }
    return NormalizedRouteHelper.decodePath(
        rawPath.substring(0, rawPath.length() - matchedPath.length()));
  }

  private static boolean hasWhitespace(final String value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isWhitespace(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the best-matching pattern of this handler was matched against the full request path.
   * Only functional routes ({@link HandlerFunction}) on Spring 5.2: since 5.2 {@code
   * RouterFunctionMapping} sets {@code BEST_MATCHING_PATTERN_ATTRIBUTE}, and before 5.3 {@code
   * RequestPredicates.PathPatternPredicate} matches {@code ServerRequest.pathContainer()} (the full
   * path); from 5.3 it matches {@code requestPath().pathWithinApplication()}. Annotated controllers
   * and other handler mappings always match the path within the application.
   */
  static boolean patternOnFullPath(final Object handler) {
    return handler instanceof HandlerFunction && FunctionalPathMode.FULL_PATH;
  }

  /**
   * Whether functional predicates of this {@code ServerRequest} type match the full path: true when
   * {@code requestPath()} (added in 5.3, together with the switch to the path within the
   * application) is absent. Unknown (reflection failure) means 5.3+ behavior.
   */
  static boolean functionalPredicatesMatchFullPath(final Class<?> serverRequestType) {
    try {
      serverRequestType.getMethod("requestPath");
      return false;
    } catch (final NoSuchMethodException e) {
      return true;
    } catch (final Throwable t) {
      return false;
    }
  }

  /**
   * Computes the functional path mode once, on the {@link ServerRequest} the application loaded:
   * this helper is injected in each application class loader, so the value is per class loader.
   */
  private static final class FunctionalPathMode {
    static final boolean FULL_PATH = functionalPredicatesMatchFullPath(ServerRequest.class);
  }

  /** Defers loading {@link AntPathMatcher} until a trailing {@code **} needs it. */
  private static final class PathMatcherHolder {
    static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
  }
}
