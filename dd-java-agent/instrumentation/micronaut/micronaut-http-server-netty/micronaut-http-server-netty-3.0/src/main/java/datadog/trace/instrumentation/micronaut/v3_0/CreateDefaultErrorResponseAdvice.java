package datadog.trace.instrumentation.micronaut.v3_0;

import static datadog.trace.instrumentation.micronaut.MicronautDecorator.DECORATE;
import static datadog.trace.instrumentation.micronaut.MicronautDecorator.SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.server.RouteExecutor;
import io.micronaut.http.server.netty.HttpDataReference;
import net.bytebuddy.asm.Advice;

public class CreateDefaultErrorResponseAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beginRequest(
      @Advice.Argument(0) final HttpRequest httpRequest,
      @Advice.Argument(1) final Throwable cause) {
    AgentSpan span = httpRequest.getAttribute(SPAN_ATTRIBUTE, AgentSpan.class).orElse(null);
    if (null == span) {
      return;
    }
    DECORATE.onError(span, cause);
  }

  private static void muzzleCheck(
      RouteExecutor routeExecutor, HttpDataReference httpDataReference) {
    // Added in 3.0.0
    routeExecutor.createDefaultErrorResponse(null, null);
    // Removed in 4.0.0
    httpDataReference.getContentType();
  }
}
