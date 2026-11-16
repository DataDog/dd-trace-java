package datadog.trace.instrumentation.springweb6;

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
import datadog.trace.api.appsec.route.SpringRouteParser;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.Controller;

public class SpringWebHttpServerDecorator
    extends HttpServerDecorator<HttpServletRequest, HttpServletRequest, HttpServletResponse, Void> {

  private static final String DD_FILTERED_SPRING_ROUTE_ALREADY_APPLIED =
      "datadog.filter.spring.route.applied";

  private static final CharSequence SPRING_HANDLER = UTF8BytesString.create("spring.handler");
  public static final CharSequence RESPONSE_RENDER = UTF8BytesString.create("response.render");

  private final CharSequence component;

  public static final SpringWebHttpServerDecorator DECORATE =
      new SpringWebHttpServerDecorator(UTF8BytesString.create("spring-web-controller"));
  public static final SpringWebHttpServerDecorator DECORATE_RENDER =
      new SpringWebHttpServerDecorator(UTF8BytesString.create("spring-webmvc"));

  public static final String DD_HANDLER_SPAN_PREFIX_KEY = "dd.handler.span.";
  public static final String DD_HANDLER_SPAN_CONTINUE_SUFFIX = ".continue";

  /** Lookup path cached by Spring's {@code UrlPathHelper} (absent in PathPatternParser mode). */
  private static final String URL_PATH_HELPER_PATH_ATTRIBUTE =
      "org.springframework.web.util.UrlPathHelper.PATH";

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

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
  protected String getRequestHeader(final HttpServletRequest request, String key) {
    return request.getHeader(key);
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
  protected void doOnRequest(
      final AgentSpan span,
      final HttpServletRequest connection,
      final HttpServletRequest request,
      final Context parentContext) {
    // FIXME: adding a filter to avoid resource name to be overridden on redirect and forwards.
    // Remove myself when jakarta.servlet will be available
    if (request != null && request.getAttribute(DD_FILTERED_SPRING_ROUTE_ALREADY_APPLIED) == null) {
      final String method = request.getMethod();
      final Object bestMatchingPattern =
          request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      if (method != null && bestMatchingPattern != null && !bestMatchingPattern.equals("/**")) {
        request.setAttribute(DD_FILTERED_SPRING_ROUTE_ALREADY_APPLIED, true);
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
    } catch (final Throwable ignored) {
      // never break the request: drop the tag
    }
  }

  /** Lookup path cached by Spring's {@code UrlPathHelper}, or null (PathPatternParser mode). */
  private static String cachedLookupPath(final HttpServletRequest request) {
    final Object lookupPath = request.getAttribute(URL_PATH_HELPER_PATH_ATTRIBUTE);
    return lookupPath instanceof String ? (String) lookupPath : null;
  }

  /** Path the pattern matched when Spring did not cache it (servlet-mapping relative). */
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
    return PATH_MATCHER.extractPathWithinPattern(pattern, lookupPath);
  }

  /**
   * Decoded path served before the pattern. With Spring's cached lookup path (AntPathMatcher mode)
   * it is the decoded context path plus the request path within the context minus the lookup path,
   * which also covers {@code alwaysUseFullPath=true}. Otherwise (PathPatternParser mode, where the
   * pattern is relative to the servlet mapping) it is the context path, plus the servlet path when
   * the DispatcherServlet has a path mapping ({@code /api/*}, pathInfo non-null).
   */
  private static String mountPrefix(
      final HttpServletRequest request, final String cachedLookupPath) {
    final String contextPath = decodedContextPath(request);
    final String beforeLookupPath = pathBeforeLookupPath(request, cachedLookupPath);
    if (beforeLookupPath != null) {
      return contextPath + beforeLookupPath;
    }
    final String servletPath = request.getServletPath();
    if (request.getPathInfo() == null || servletPath == null) {
      return contextPath;
    }
    return contextPath + servletPath;
  }

  /**
   * Decoded request path within the context, without {@code ;} parameters, minus the lookup path;
   * null when there is no lookup path or the request path does not end with it.
   */
  private static String pathBeforeLookupPath(
      final HttpServletRequest request, final String lookupPath) {
    if (lookupPath == null) {
      return null;
    }
    final String requestUri = request.getRequestURI();
    final String contextPath = request.getContextPath();
    if (requestUri == null || (contextPath != null && !requestUri.startsWith(contextPath))) {
      return null;
    }
    final String pathWithinContext =
        NormalizedRouteHelper.decodePath(
            removeSemicolonContent(
                contextPath == null ? requestUri : requestUri.substring(contextPath.length())));
    if (!pathWithinContext.endsWith(lookupPath)) {
      return null;
    }
    return pathWithinContext.substring(0, pathWithinContext.length() - lookupPath.length());
  }

  /** Removes {@code ;} path parameters as Spring's {@code UrlPathHelper} does by default. */
  private static String removeSemicolonContent(final String path) {
    int semicolon = path.indexOf(';');
    if (semicolon < 0) {
      return path;
    }
    final StringBuilder sb = new StringBuilder(path.length());
    int start = 0;
    while (semicolon >= 0) {
      sb.append(path, start, semicolon);
      final int slash = path.indexOf('/', semicolon);
      if (slash < 0) {
        return sb.toString();
      }
      start = slash;
      semicolon = path.indexOf(';', start);
    }
    return sb.append(path, start, path.length()).toString();
  }

  /** Servlet containers return the context path undecoded; the route renderer expects decoded. */
  private static String decodedContextPath(final HttpServletRequest request) {
    final String contextPath = request.getContextPath();
    return contextPath == null ? "" : NormalizedRouteHelper.decodePath(contextPath);
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
