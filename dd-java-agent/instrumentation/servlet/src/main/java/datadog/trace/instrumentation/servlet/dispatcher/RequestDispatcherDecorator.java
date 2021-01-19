package datadog.trace.instrumentation.servlet.dispatcher;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.BitSet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

public class RequestDispatcherDecorator extends BaseDecorator {
  public static final RequestDispatcherDecorator DECORATE = new RequestDispatcherDecorator();
  public static final CharSequence JAVA_WEB_SERVLET_DISPATCHER =
      UTF8BytesString.createConstant("java-web-servlet-dispatcher");
  private static final BitSet SERVER_ERROR_STATUSES = Config.get().getHttpServerErrorStatuses();
  private static final Integer SERVER_ERROR = 500;
  public static final String DD_CONTEXT_PATH_ATTRIBUTE = "datadog.context.path";
  public static final String DD_SERVLET_PATH_ATTRIBUTE = "datadog.servlet.path";

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
}
