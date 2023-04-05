package datadog.trace.instrumentation.micronaut;

import static datadog.trace.instrumentation.micronaut.MicronautDecorator.DECORATE;
import static datadog.trace.instrumentation.micronaut.MicronautDecorator.PARENT_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.micronaut.MicronautDecorator.SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.micronaut.http.server.netty.NettyHttpRequest;
import io.micronaut.web.router.RouteMatch;
import net.bytebuddy.asm.Advice;

public class HandleRouteMatchAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beginRequest(
      @Advice.Argument(0) final RouteMatch<?> route,
      @Advice.Argument(1) final NettyHttpRequest<?> request) {
    AgentSpan span = request.getAttribute(SPAN_ATTRIBUTE, AgentSpan.class).orElse(null);
    if (null == span) {
      return;
    }
    final AgentSpan nettySpan =
        request.getAttribute(PARENT_SPAN_ATTRIBUTE, AgentSpan.class).orElse(null);

    DECORATE.onMicronautSpan(span, nettySpan, request, route);
  }
}
