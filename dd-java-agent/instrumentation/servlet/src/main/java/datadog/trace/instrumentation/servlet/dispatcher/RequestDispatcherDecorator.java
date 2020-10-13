package datadog.trace.instrumentation.servlet.dispatcher;

import static datadog.trace.api.cache.RadixTreeCache.HTTP_STATUSES;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.servlet.ServletException;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

public class RequestDispatcherDecorator extends BaseDecorator {
  public static final RequestDispatcherDecorator DECORATE = new RequestDispatcherDecorator();
  public static final CharSequence JAVA_WEB_SERVLET_DISPATCHER =
      UTF8BytesString.createConstant("java-web-servlet-dispatcher");

  private static final MethodHandle STATUS_CODE_METHOD;

  static {
    // to satisfy the compiler that STATUS_CODE_METHOD is only assigned once
    // use a local variable
    MethodHandle local = null;
    try {
      local =
          MethodHandles.publicLookup()
              .findVirtual(
                  HttpServletResponse.class, "getStatus", MethodType.methodType(int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      // ignore. getStatus was added in servlet 3
    }

    STATUS_CODE_METHOD = local;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"servlet", "servlet-dispatcher"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return JAVA_WEB_SERVLET_DISPATCHER;
  }

  @Override
  public AgentSpan onError(final AgentSpan span, final Throwable throwable) {
    if (throwable instanceof ServletException && throwable.getCause() != null) {
      super.onError(span, throwable.getCause());
    } else {
      super.onError(span, throwable);
    }
    return span;
  }

  public AgentSpan onResponse(
      final AgentSpan span, final ServletResponse response, Throwable throwable) {
    if (response instanceof HttpServletResponse && STATUS_CODE_METHOD != null) {
      try {
        int status = (int) STATUS_CODE_METHOD.invokeExact((HttpServletResponse) response);

        if (throwable != null && status == HttpServletResponse.SC_OK) {
          span.setTag(Tags.HTTP_STATUS, HTTP_STATUSES.get(500));
        } else {
          span.setTag(Tags.HTTP_STATUS, HTTP_STATUSES.get(status));
        }

        if (status == 404) {
          span.setResourceName("404");
        }
      } catch (Throwable ignored) {
      }
    }
    return span;
  }
}
