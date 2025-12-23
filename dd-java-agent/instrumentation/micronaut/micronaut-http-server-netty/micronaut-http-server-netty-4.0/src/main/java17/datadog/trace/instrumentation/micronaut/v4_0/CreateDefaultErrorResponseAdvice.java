package datadog.trace.instrumentation.micronaut.v4_0;

import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.DECORATE;
import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.micronaut.http.HttpRequest;
import net.bytebuddy.asm.Advice;

public class CreateDefaultErrorResponseAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void captureError(
      @Advice.Argument(0) final HttpRequest httpRequest,
      @Advice.Argument(1) final Throwable cause) {
    AgentSpan span = httpRequest.getAttribute(SPAN_ATTRIBUTE, AgentSpan.class).orElse(null);
    if (null == span) {
      return;
    }
    DECORATE.onError(span, cause);
  }
}
