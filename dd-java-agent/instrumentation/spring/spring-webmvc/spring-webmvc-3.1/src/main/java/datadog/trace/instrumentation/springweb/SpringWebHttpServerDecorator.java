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

  /**
   * Spring 5.3+ {@code ServletRequestPathUtils.PATH_ATTRIBUTE}: request path parsed for
   * PathPatternParser mode, read by name.
   */
  private static final String SERVLET_REQUEST_PATH_ATTRIBUTE =
      "org.springframework.web.util.ServletRequestPathUtils.PATH";

  /** {@code RequestDispatcher.INCLUDE_*} attributes, read by name (the mapping is Servlet 4+). */
  private static final String INCLUDE_REQUEST_URI = "javax.servlet.include.request_uri";

  private static final String INCLUDE_CONTEXT_PATH = "javax.servlet.include.context_path";
  private static final String INCLUDE_SERVLET_PATH = "javax.servlet.include.servlet_path";
  private static final String INCLUDE_PATH_INFO = "javax.servlet.include.path_info";
  private static final String INCLUDE_MAPPING = "javax.servlet.include.mapping";

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
   * served before Spring's lookup path (see {@link ServletMountPrefix}). During an include the
   * request values come from the {@code javax.servlet.include.*} attributes, as Spring's {@code
   * UrlPathHelper} reads them.
   */
  static void tagNormalizedRoute(
      final AgentSpan span, final HttpServletRequest request, final String pattern) {
    try {
      final RouteTemplate template = SpringRouteParser.cached(pattern);
      if (!template.isExpressible()) {
        return;
      }
      final boolean include = request.getAttribute(INCLUDE_REQUEST_URI) != null;
      final String requestUri =
          include ? (String) request.getAttribute(INCLUDE_REQUEST_URI) : request.getRequestURI();
      final String contextPath =
          include
              ? includeOr(request, INCLUDE_CONTEXT_PATH, request.getContextPath())
              : request.getContextPath();
      final String servletPath =
          include
              ? includeOr(request, INCLUDE_SERVLET_PATH, request.getServletPath())
              : request.getServletPath();
      final String pathInfo =
          include ? (String) request.getAttribute(INCLUDE_PATH_INFO) : request.getPathInfo();
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
        pathMapping = pathInfo != null || isPathMapping(request, include);
        prefix = ServletMountPrefix.fallback(contextPath, servletPath, pathMapping);
      }
      if (template.staticRoute() != null) {
        // nothing depends on the request: no presence to resolve
        NormalizedRouteHelper.tag(span, template, prefix, null);
        return;
      }
      String lookupPath = cachedLookupPath;
      if (lookupPath == null) {
        lookupPath = parsedLookupPath(request);
      }
      if (lookupPath == null) {
        lookupPath = fallbackLookupPath(servletPath, pathInfo, pathMapping);
      }
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
   * Path within the application parsed by Spring 5.3+ for PathPatternParser mode (raw, after the
   * context path and the servlet mount), or null when not parsed. Read by reflection: {@code
   * RequestPath} does not exist before 5.3.
   */
  private static String parsedLookupPath(final HttpServletRequest request) {
    final Object requestPath = request.getAttribute(SERVLET_REQUEST_PATH_ATTRIBUTE);
    return requestPath == null ? null : RequestPathHolder.pathWithinApplication(requestPath);
  }

  /**
   * Whether the DispatcherServlet has a path mapping ({@code /api/*}), from the Servlet 4+ {@code
   * HttpServletMapping} read by reflection; false when unknown (Servlet 3).
   */
  private static boolean isPathMapping(final HttpServletRequest request, final boolean include) {
    final Object included = include ? request.getAttribute(INCLUDE_MAPPING) : null;
    return ServletMappingHolder.isPathMapping(request, included);
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

  /** Defers loading {@link AntPathMatcher} until a trailing {@code **} needs it. */
  private static final class PathMatcherHolder {
    static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
  }

  /**
   * Spring 5.3+ {@code RequestPath} accessors, resolved once when the attribute is first seen; null
   * methods when absent.
   */
  private static final class RequestPathHolder {
    private static final Class<?> REQUEST_PATH;
    private static final Method PATH_WITHIN_APPLICATION;
    private static final Method VALUE;

    static {
      Class<?> requestPath = null;
      Method pathWithinApplication = null;
      Method value = null;
      try {
        final ClassLoader loader = HandlerMapping.class.getClassLoader();
        requestPath = Class.forName("org.springframework.http.server.RequestPath", false, loader);
        pathWithinApplication = requestPath.getMethod("pathWithinApplication");
        value =
            Class.forName("org.springframework.http.server.PathContainer", false, loader)
                .getMethod("value");
      } catch (final Throwable t) {
        requestPath = null;
        pathWithinApplication = null;
        value = null;
      }
      REQUEST_PATH = requestPath;
      PATH_WITHIN_APPLICATION = pathWithinApplication;
      VALUE = value;
    }

    static String pathWithinApplication(final Object requestPath) {
      if (REQUEST_PATH == null || !REQUEST_PATH.isInstance(requestPath)) {
        return null;
      }
      try {
        final Object path = PATH_WITHIN_APPLICATION.invoke(requestPath, (Object[]) null);
        final Object value = path == null ? null : VALUE.invoke(path, (Object[]) null);
        return value instanceof String ? (String) value : null;
      } catch (final Throwable t) {
        return null;
      }
    }
  }

  /**
   * Servlet 4+ {@code HttpServletRequest.getHttpServletMapping().getMappingMatch()}, resolved once;
   * null methods on Servlet 3.
   */
  private static final class ServletMappingHolder {
    private static final Class<?> HTTP_SERVLET_MAPPING;
    private static final Method GET_HTTP_SERVLET_MAPPING;
    private static final Method GET_MAPPING_MATCH;

    static {
      Class<?> mapping = null;
      Method getHttpServletMapping = null;
      Method getMappingMatch = null;
      try {
        getHttpServletMapping = HttpServletRequest.class.getMethod("getHttpServletMapping");
        mapping =
            Class.forName(
                "javax.servlet.http.HttpServletMapping",
                false,
                HttpServletRequest.class.getClassLoader());
        getMappingMatch = mapping.getMethod("getMappingMatch");
      } catch (final Throwable t) {
        mapping = null;
        getHttpServletMapping = null;
        getMappingMatch = null;
      }
      HTTP_SERVLET_MAPPING = mapping;
      GET_HTTP_SERVLET_MAPPING = getHttpServletMapping;
      GET_MAPPING_MATCH = getMappingMatch;
    }

    static boolean isPathMapping(final HttpServletRequest request, final Object included) {
      if (GET_MAPPING_MATCH == null) {
        return false;
      }
      try {
        final Object mapping =
            HTTP_SERVLET_MAPPING.isInstance(included)
                ? included
                : GET_HTTP_SERVLET_MAPPING.invoke(request, (Object[]) null);
        if (mapping == null) {
          return false;
        }
        final Object match = GET_MAPPING_MATCH.invoke(mapping, (Object[]) null);
        return match instanceof Enum && "PATH".equals(((Enum<?>) match).name());
      } catch (final Throwable t) {
        return false;
      }
    }
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
