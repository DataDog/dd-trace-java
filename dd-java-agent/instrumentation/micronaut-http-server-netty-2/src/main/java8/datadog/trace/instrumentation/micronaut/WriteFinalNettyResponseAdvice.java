package datadog.trace.instrumentation.micronaut;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.micronaut.MicronautDecorator.DECORATE;
import static datadog.trace.instrumentation.micronaut.MicronautDecorator.SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import net.bytebuddy.asm.Advice;

public class WriteFinalNettyResponseAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beginRequest(
      @Advice.Argument(0) final MutableHttpResponse<?> message,
      @Advice.Argument(1) final HttpRequest<?> request) {
    AgentSpan span = request.getAttribute(SPAN_ATTRIBUTE, AgentSpan.class).orElse(null);
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
