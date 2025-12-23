package datadog.trace.instrumentation.micronaut.v4_0;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.DECORATE;
import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.server.netty.NettyHttpRequest;
import net.bytebuddy.asm.Advice;

public class EncodeHttpResponseAdvice2 {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void finishHandlerSpan(
      @Advice.Argument(0) final NettyHttpRequest<?> request,
      @Advice.Argument(1) final HttpResponse<?> message) {
    AgentSpan span = request.removeAttribute(SPAN_ATTRIBUTE, AgentSpan.class).orElse(null);
    if (null == span) {
      return;
    }

    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.onResponse(span, message);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
