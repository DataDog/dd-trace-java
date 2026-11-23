package datadog.trace.instrumentation.springweb;

import static datadog.trace.api.KnownTags.VIEW_NAME;
import static datadog.trace.api.KnownTags.VIEW_TYPE;
import static datadog.trace.api.Pair.of;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.context.Context;
import datadog.trace.api.GenericClassValue;
import datadog.trace.api.Pair;
import datadog.trace.api.appsec.route.BoundValuesPresenceResolver;
import datadog.trace.api.appsec.route.NormalizedRouteHelper;
import datadog.trace.api.appsec.route.RouteTemplate;
import datadog.trace.api.appsec.route.ServletMountPrefix;
import datadog.trace.api.appsec.route.SpringRouteParser;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.lang.reflect.Method;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.Controller;

public class SpringWebHttpServerDecorator
    extends HttpServerDecorator<HttpServletRequest, HttpServletRequest, HttpServletResponse, Void> {

  private static final CharSequence SPRING_HANDLER = UTF8BytesString.create("spring.handler");
  public static final CharSequence RESPONSE_RENDER = UTF8BytesString.create("response.render");

  private final CharSequence component;

  public static final SpringWebHttpServerDecorator DECORATE =
      new SpringWebHttpServerDecorator(UTF8BytesString.create("spring-web-controller"));
  public static final SpringWebHttpServerDecorator DECORATE_RENDER =
      new SpringWebHttpServerDecorator(UTF8BytesString.create("spring-webmvc"));
  public static final String DD_HANDLER_SPAN_PREFIX_KEY = "dd.handler.span.";
  public static final String DD_HANDLER_SPAN_CONTINUE_SUFFIX = ".continue";

  /**
   * Lookup path cached by Spring 5.3+ {@code UrlPathHelper}, read by name: absent before 5.3 and in
   * 5.3+ PathPatternParser mode.
   */
  private static final String URL_PATH_HELPER_PATH_ATTRIBUTE =
      "org.springframework.web.util.UrlPathHelper.PATH";

  private static final Logger log = LoggerFactory.getLogger(SpringWebHttpServerDecorator.class);

  private static final ClassValue<Pair<String, String>> HANDLER_SPAN_KEYS =
      GenericClassValue.of(type -> {
        String spanKey = DD_HANDLER_SPAN_PREFIX_KEY + type.getName();
        return of(spanKey, spanKey + DD_HANDLER_SPAN_CONTINUE_SUFFIX);
      });

  /** Returns the span attribute key on the left and the continuation attribute key on the right. */
  public static Pair<String, String> handlerSpanKeys(Class<?> handlerClass) {
    return HANDLER_SPAN_KEYS.get(handlerClass);
  }

  public SpringWebHttpServerDecorator(CharSequence component) {
    this.component = component;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spring-web"};
  }

  @Override
  protected CharSequence component() {
    return component;
  }

  @Override
  protected boolean traceAnalyticsDefault() {
    return false;
  }

  @Override
  protected AgentPropagation.ContextVisitor<Void> getter() {
    return null;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServletResponse> responseGetter() {
    return null;
  }

  @Override
  public CharSequence spanName() {
    return SPRING_HANDLER;
  }

  @Override
  protected String method(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getMethod();
  }

  @Override
  protected URIDataAdapter url(final HttpServletRequest httpServletRequest) {
    return new ServletRequestURIAdapter(httpServletRequest);
  }

  @Override
  protected String peerHostIP(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getRemoteAddr();
  }

  @Override
  protected int peerPort(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getRemotePort();
  }

  @Override
  protected int status(final HttpServletResponse httpServletResponse) {
    return httpServletResponse.getStatus();
  }

  @Override
  protected String getRequestHeader(final HttpServletRequest request, String key) {
    return request.getHeader(key);
  }

  @Override
  protected void doOnRequest(
      final AgentSpan span,
      final HttpServletRequest connection,
      final HttpServletRequest request,
      final Context parentContext) {
    if (request != null) {
      final String method = request.getMethod();
      final Object bestMatchingPattern =
          request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      if (method != null && bestMatchingPattern != null && !bestMatchingPattern.equals("/**")) {
        final String pattern = bestMatchingPattern.toString();
        HTTP_RESOURCE_DECORATOR.withRoute(span, method, pattern);
        if (NormalizedRouteHelper.isEnabled()) {
          tagNormalizedRoute(span, request, pattern);
        }
      }
    }
  }

  /**
   * Sets {@code _dd.appsec.normalized_route} from the matched pattern, under the mount prefix
   * served before Spring's lookup path (see {@link #mountPrefix}).
   */
  private static void tagNormalizedRoute(
      final AgentSpan span, final HttpServletRequest request, final String pattern) {
    try {
      final RouteTemplate template = SpringRouteParser.cached(pattern);
      if (!template.isExpressible()) {
        return;
      }
      final String cachedLookupPath = cachedLookupPath(request);
      final String prefix = mountPrefix(request, cachedLookupPath);
      if (template.staticRoute() != null) {
        // nothing depends on the request: no presence to resolve
        NormalizedRouteHelper.tag(span, template, prefix, null);
        return;
      }
      final String lookupPath =
          cachedLookupPath != null ? cachedLookupPath : fallbackLookupPath(request);
      NormalizedRouteHelper.tag(
          span,
          template,
          prefix,
          BoundValuesPresenceResolver.fromRaw(
              template,
              request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE),
              unnamedCatchAllRemainder(template, pattern, lookupPath),
              lookupPath != null && lookupPath.endsWith("/")));
    } catch (final Throwable t) {
      // never break the request: drop the tag
      log.debug("Failed to compute the normalized route", t);
    }
  }

  /**
   * Lookup path cached by Spring 5.3+ {@code UrlPathHelper}, or null (before 5.3,
   * PathPatternParser).
   */
  private static String cachedLookupPath(final HttpServletRequest request) {
    final Object lookupPath = request.getAttribute(URL_PATH_HELPER_PATH_ATTRIBUTE);
    return lookupPath instanceof String ? (String) lookupPath : null;
  }

  /** Lookup path of Spring's default {@code UrlPathHelper} when it is not cached. */
  private static String fallbackLookupPath(final HttpServletRequest request) {
    final String pathInfo = request.getPathInfo();
    return pathInfo != null ? pathInfo : request.getServletPath();
  }

  /** Part matched by a trailing {@code **}, computed by Spring's own matcher; null if not used. */
  private static String unnamedCatchAllRemainder(
      final RouteTemplate template, final String pattern, final String lookupPath) {
    if (lookupPath == null || !template.needsUnnamedCatchAllRemainder()) {
      return null;
    }
    return PathMatcherHolder.PATH_MATCHER.extractPathWithinPattern(pattern, lookupPath);
  }

  /**
   * Decoded path served before the pattern (see {@link ServletMountPrefix}). Spring's cached lookup
   * path is absent before 5.3 and in 5.3+ PathPatternParser mode; without it the prefix is the
   * context path, plus the servlet path when the DispatcherServlet has a path mapping ({@code
   * /api/*}). With it ({@code alwaysUseFullPath=true} included) the prefix is exact. Limitation,
   * only when it is absent and before 5.3: with {@code alwaysUseFullPath=true} a path-mapped
   * servlet gets its servlet path twice (PathPatternParser mode ignores {@code alwaysUseFullPath}).
   */
  private static String mountPrefix(
      final HttpServletRequest request, final String cachedLookupPath) {
    return ServletMountPrefix.of(
        request.getRequestURI(),
        request.getContextPath(),
        request.getServletPath(),
        request.getPathInfo(),
        cachedLookupPath,
        request.getCharacterEncoding());
  }

  /** Defers loading {@link AntPathMatcher} until a trailing {@code **} needs it. */
  private static final class PathMatcherHolder {
    static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
  }

  public void onHandle(final AgentSpan span, final Object handler) {
    if (handler instanceof HandlerMethod) {
      // name span based on the class and method name defined in the handler
      final Method method = ((HandlerMethod) handler).getMethod();
      span.setResourceName(
          DECORATE.spanNameForMethod(method.getDeclaringClass(), method.getName()));
    } else {
      span.setResourceName(DECORATE.spanNameForMethod(handler.getClass(), getMethodName(handler)));
    }
  }

  private String getMethodName(final Object handler) {
    if (handler instanceof HttpRequestHandler || handler instanceof Controller) {
      // org.springframework.web.servlet.mvc.HttpRequestHandlerAdapter
      // org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter
      return "handleRequest";
    } else if (handler instanceof Servlet) {
      // org.springframework.web.servlet.handler.SimpleServletHandlerAdapter
      return "service";
    } else {
      // perhaps org.springframework.web.servlet.mvc.annotation.AnnotationMethodHandlerAdapter
      return "<annotation>";
    }
  }

  public void onRender(final AgentSpan span, final ModelAndView mv) {
    final String viewName = mv.getViewName();
    if (viewName != null) {
      span.setTag(VIEW_NAME, viewName);
      span.setResourceName(viewName);
    }
    if (mv.getView() != null) {
      span.setTag(VIEW_TYPE, className(mv.getView().getClass()));
    }
  }
}
