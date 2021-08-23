package datadog.trace.instrumentation.micronaut;

import static datadog.trace.instrumentation.micronaut.MicronautDecorator.DECORATE;
import static datadog.trace.instrumentation.micronaut.MicronautDecorator.PARENT_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.micronaut.MicronautDecorator.SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.micronaut.http.HttpVersion;
import io.micronaut.http.MediaTypeConverter;
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

  private static void muzzleCheck(MediaTypeConverter mediaTypeConverter) {
    // Removed in 3.0.0
    mediaTypeConverter.convert(null, null);
    // Added in 2.0.0
    HttpVersion version = HttpVersion.HTTP_2_0;
  }
}
