package datadog.trace.instrumentation.micronaut.v4_0;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.DECORATE;
import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.PARENT_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.micronaut.http.server.netty.NettyHttpRequest;
import net.bytebuddy.asm.Advice;

public class ChannelAcceptAdvice2 {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope beginRequest(@Advice.Argument(0) final NettyHttpRequest request) {
    final AgentSpan nettySpan = activeSpan();

    if (request == null || nettySpan == null) {
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
}
