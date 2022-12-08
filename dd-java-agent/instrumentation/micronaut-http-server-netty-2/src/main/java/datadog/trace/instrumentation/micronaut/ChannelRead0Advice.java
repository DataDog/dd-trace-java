package datadog.trace.instrumentation.micronaut;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.micronaut.MicronautDecorator.DECORATE;
import static datadog.trace.instrumentation.micronaut.MicronautDecorator.PARENT_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.micronaut.MicronautDecorator.SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpVersion;
import io.micronaut.http.MediaTypeConverter;
import net.bytebuddy.asm.Advice;

public class ChannelRead0Advice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope beginRequest(@Advice.Argument(1) final HttpRequest<?> request) {
    final AgentSpan nettySpan = activeSpan();
    if (null == nettySpan) {
      // Micronaut-netty uses Netty so there needs to be a Netty span
      return null;
    }
    final AgentSpan span = startSpan(DECORATE.spanName()).setMeasured(true);
    DECORATE.afterStart(span);
    request.setAttribute(SPAN_ATTRIBUTE, span);
    request.setAttribute(PARENT_SPAN_ATTRIBUTE, nettySpan);

    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void endRequest(@Advice.Enter final AgentScope scope) {
    if (scope != null) {
      scope.close();
    }
  }

  private static void muzzleCheck(MediaTypeConverter mediaTypeConverter) {
    // Removed in 3.0.0
    mediaTypeConverter.convert(null, null);
    // Added in 2.0.0
    HttpVersion version = HttpVersion.HTTP_2_0;
  }
}
