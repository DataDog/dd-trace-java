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
import datadog.trace.api.appsec.route.ServletMountPrefix;
import datadog.trace.api.appsec.route.SpringRouteParser;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.MappingMatch;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.RequestPath;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.Controller;
import org.springframework.web.util.ServletRequestPathUtils;

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
   * served before Spring's lookup path (see {@link ServletMountPrefix}). During an include the
   * request values come from the {@code jakarta.servlet.include.*} attributes, as Spring's {@code
   * UrlPathHelper} reads them.
   */
  static void tagNormalizedRoute(
      final AgentSpan span, final HttpServletRequest request, final String pattern) {
    try {
      final RouteTemplate template = SpringRouteParser.cached(pattern);
      if (!template.isExpressible()) {
        return;
      }
      final boolean include = request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;
      final String requestUri =
          include
              ? (String) request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI)
              : request.getRequestURI();
      final String contextPath =
          include
              ? includeOr(request, RequestDispatcher.INCLUDE_CONTEXT_PATH, request.getContextPath())
              : request.getContextPath();
      final String servletPath =
          include
              ? includeOr(request, RequestDispatcher.INCLUDE_SERVLET_PATH, request.getServletPath())
              : request.getServletPath();
      final String pathInfo =
          include
              ? (String) request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO)
              : request.getPathInfo();
      final String cachedLookupPath = cachedLookupPath(request);
      String prefix =
          ServletMountPrefix.inferred(
              requestUri,
              contextPath,
              servletPath,
              cachedLookupPath,
              request.getCharacterEncoding());
      boolean pathMapping = false;
      if (prefix == null) {
        // a lookup path from UrlPathHelper (cached, or legacy before Spring 5.3) is the path within
        // the application on the exact mount URL of a path mapping: only a parsed request path
        // starts after the servlet path then
        pathMapping =
            pathInfo != null
                || (cachedLookupPath == null
                    && request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE) != null
                    && isPathMapping(request, include));
        prefix = ServletMountPrefix.fallback(contextPath, servletPath, pathMapping);
      }
      if (template.staticRoute() != null) {
        // nothing depends on the request: no presence to resolve
        NormalizedRouteHelper.tag(span, template, prefix, null);
        return;
      }
      String lookupPath = cachedLookupPath;
      boolean rawLookupPath = false;
      if (lookupPath == null) {
        lookupPath = parsedLookupPath(request);
        rawLookupPath = lookupPath != null;
      }
      if (lookupPath == null) {
        lookupPath = fallbackLookupPath(servletPath, pathInfo, pathMapping);
      }
      // extracted from the untouched lookup path: the segment positions are the matched ones
      final String extracted = unnamedCatchAllRemainder(template, pattern, lookupPath);
      final String remainder = rawLookupPath ? matchedValue(extracted) : extracted;
      NormalizedRouteHelper.tag(
          span,
          template,
          prefix,
          BoundValuesPresenceResolver.fromRaw(
              template,
              request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE),
              remainder,
              lookupPath != null
                  && (lookupPath.endsWith("/")
                      // a raw remainder made only of ';' content followed a '/'
                      || (remainder != null && remainder.isEmpty() && !extracted.isEmpty()))));
    } catch (final Throwable t) {
      // never break the request: drop the tag
      log.debug("Failed to compute the normalized route", t);
    }
  }

  private static String includeOr(
      final HttpServletRequest request, final String attribute, final String value) {
    final Object included = request.getAttribute(attribute);
    return included instanceof String ? (String) included : value;
  }

  /** Lookup path cached by Spring's {@code UrlPathHelper}, or null (PathPatternParser mode). */
  private static String cachedLookupPath(final HttpServletRequest request) {
    final Object lookupPath = request.getAttribute(URL_PATH_HELPER_PATH_ATTRIBUTE);
    return lookupPath instanceof String ? (String) lookupPath : null;
  }

  /**
   * Path within the application parsed by Spring for PathPatternParser mode (raw, after the context
   * path and the servlet mount), or null when not parsed.
   */
  private static String parsedLookupPath(final HttpServletRequest request) {
    final Object requestPath = request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
    return requestPath instanceof RequestPath
        ? ((RequestPath) requestPath).pathWithinApplication().value()
        : null;
  }

  /** Whether the DispatcherServlet has a path mapping ({@code /api/*}); false when unknown. */
  private static boolean isPathMapping(final HttpServletRequest request, final boolean include) {
    try {
      final Object included =
          include ? request.getAttribute(RequestDispatcher.INCLUDE_MAPPING) : null;
      final HttpServletMapping mapping =
          included instanceof HttpServletMapping
              ? (HttpServletMapping) included
              : request.getHttpServletMapping();
      return mapping != null && mapping.getMappingMatch() == MappingMatch.PATH;
    } catch (final Throwable t) {
      return false;
    }
  }

  /** Path the pattern matched when Spring did not cache nor parse it (servlet-mapping relative). */
  private static String fallbackLookupPath(
      final String servletPath, final String pathInfo, final boolean pathMapping) {
    if (pathInfo != null) {
      return pathInfo;
    }
    // exact mount URL of a path mapping: nothing after the servlet path
    return pathMapping ? "" : servletPath;
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
   * Value Spring matched for a remainder of the raw parsed request path: the {@code ;} content of
   * each segment removed, then decoded. Same instance when there is no {@code ;} nor {@code %}.
   */
  private static String matchedValue(final String rawRemainder) {
    return rawRemainder == null
        ? null
        : NormalizedRouteHelper.decodePath(ServletMountPrefix.removeSemicolonContent(rawRemainder));
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
