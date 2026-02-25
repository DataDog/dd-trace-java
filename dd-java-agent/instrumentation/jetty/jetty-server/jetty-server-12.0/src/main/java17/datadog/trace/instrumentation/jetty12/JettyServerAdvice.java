package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty12.JettyDecorator.DECORATE;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

public class JettyServerAdvice {
  public static void finishSpan(Request request, Response response, Throwable failure) {
    Object contextObj = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
    if (!(contextObj instanceof Context)) {
      return;
    }

    final Context context = (Context) contextObj;
    final AgentSpan span = AgentSpan.fromContext(context);
    if (span != null) {
      DECORATE.onResponse(span, request, response);
      if (failure != null) {
        DECORATE.onError(span, failure);
      }
      DECORATE.beforeFinish(context);
      span.finish();
    }
    request.removeAttribute(DD_CONTEXT_ATTRIBUTE);
  }
}
