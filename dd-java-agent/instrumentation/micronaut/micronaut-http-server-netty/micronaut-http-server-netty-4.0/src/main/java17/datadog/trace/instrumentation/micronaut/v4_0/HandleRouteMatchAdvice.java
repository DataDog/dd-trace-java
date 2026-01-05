package datadog.trace.instrumentation.micronaut.v4_0;

import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.DECORATE;
import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.PARENT_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.micronaut.http.HttpRequest;
import io.micronaut.web.router.UriRouteMatch;
import net.bytebuddy.asm.Advice;

public class HandleRouteMatchAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void captureRoute(
      @Advice.Argument(0) final HttpRequest<?> request,
      @Advice.Return final UriRouteMatch routeMatch) {
    if (routeMatch == null) {
      return;
    }
    AgentSpan span = request.getAttribute(SPAN_ATTRIBUTE, AgentSpan.class).orElse(null);
    if (span == null) {
      return;
    }
    final AgentSpan nettySpan =
        request.getAttribute(PARENT_SPAN_ATTRIBUTE, AgentSpan.class).orElse(null);
    if (nettySpan != null) {
      DECORATE.onMicronautSpan(span, nettySpan, request, routeMatch);
    }
  }
}
