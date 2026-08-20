package datadog.trace.instrumentation.springweb;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.ClassHierarchyIterable;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.lang.reflect.Method;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.Controller;

public class SpringWebHttpServerDecorator
    extends HttpServerDecorator<HttpServletRequest, HttpServletRequest, HttpServletResponse, Void> {

  // ResponseStatusException was added in Spring 5.0; this module also supports Spring 3.1-4.x,
  // so it can't be referenced directly. Resolved reflectively, once, and reused.
  private static final Method RESPONSE_STATUS_EXCEPTION_GET_STATUS =
      findResponseStatusExceptionGetStatus();

  private static Method findResponseStatusExceptionGetStatus() {
    try {
      Class<?> responseStatusExceptionClass =
          Class.forName(
              "org.springframework.web.server.ResponseStatusException",
              false,
              SpringWebHttpServerDecorator.class.getClassLoader());
      return responseStatusExceptionClass.getMethod("getStatus");
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      return null;
    }
  }

  // ResponseStatus#code() was added in Spring 4.2 as an alias of value(); this module compiles
  // against Spring 3.1, so it can't be referenced directly. Resolved reflectively, once, and
  // reused. Plain reflection doesn't resolve Spring's @AliasFor, so if a caller only set code(),
  // value() still reports its own default rather than the value mirrored from code().
  private static final Method RESPONSE_STATUS_CODE = findResponseStatusCode();

  private static Method findResponseStatusCode() {
    try {
      return ResponseStatus.class.getMethod("code");
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

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
  protected void doOnError(final AgentSpan span, final Throwable throwable, byte errorPriority) {
    // Walk the cause chain looking for a status the exception itself carries (@ResponseStatus, or
    // a ResponseStatusException on Spring 5+). If the mapped status isn't one of the configured
    // "server error" statuses, this isn't really an error from the caller's point of view (e.g. a
    // 404 mapping), even though a Java exception was thrown to get there.
    Integer status = extractResponseStatus(throwable);
    if (status != null) {
      span.addThrowable(throwable, ErrorPriorities.HTTP_SERVER_DECORATOR);
      span.setError(
          Config.get().getHttpServerErrorStatuses().get(status),
          ErrorPriorities.HTTP_SERVER_DECORATOR);
      return;
    }
    super.doOnError(span, throwable, errorPriority);
  }

  private static Integer extractResponseStatus(final Throwable throwable) {
    Throwable current = throwable;
    for (int depth = 0; current != null && depth < 5; depth++, current = current.getCause()) {
      if (RESPONSE_STATUS_EXCEPTION_GET_STATUS != null
          && RESPONSE_STATUS_EXCEPTION_GET_STATUS.getDeclaringClass().isInstance(current)) {
        try {
          Object httpStatus = RESPONSE_STATUS_EXCEPTION_GET_STATUS.invoke(current);
          if (httpStatus instanceof HttpStatus) {
            return ((HttpStatus) httpStatus).value();
          }
        } catch (Throwable ignored) {
          // fall through to the @ResponseStatus check below
        }
      }
      for (Class<?> type : new ClassHierarchyIterable(current.getClass())) {
        ResponseStatus responseStatus = type.getAnnotation(ResponseStatus.class);
        if (responseStatus != null) {
          return responseStatusCode(responseStatus);
        }
      }
    }
    return null;
  }

  private static int responseStatusCode(final ResponseStatus responseStatus) {
    if (RESPONSE_STATUS_CODE != null) {
      try {
        HttpStatus code = (HttpStatus) RESPONSE_STATUS_CODE.invoke(responseStatus);
        if (code != HttpStatus.INTERNAL_SERVER_ERROR) {
          return code.value();
        }
      } catch (Throwable ignored) {
        // fall through to value() below
      }
    }
    return responseStatus.value().value();
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
        HTTP_RESOURCE_DECORATOR.withRoute(span, method, bestMatchingPattern.toString());
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
      span.setTag("view.name", viewName);
      span.setResourceName(viewName);
    }
    if (mv.getView() != null) {
      span.setTag("view.type", className(mv.getView().getClass()));
    }
  }
}
