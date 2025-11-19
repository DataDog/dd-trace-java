package datadog.trace.instrumentation.springweb6;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
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
  public AgentSpan onRequest(
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
        HTTP_RESOURCE_DECORATOR.withRoute(span, method, bestMatchingPattern.toString());
      }
    }
    return span;
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

  public AgentSpan onRender(final AgentSpan span, final ModelAndView mv) {
    final String viewName = mv.getViewName();
    if (viewName != null) {
      span.setTag("view.name", viewName);
      span.setResourceName(viewName);
    }
    if (mv.getView() != null) {
      span.setTag("view.type", className(mv.getView().getClass()));
    }
    return span;
  }
}
