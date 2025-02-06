package datadog.trace.instrumentation.jetty10;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

public class OnCompletedAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope enter(@Advice.This final HttpChannel channel) {
    Request req = channel.getRequest();
    Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
    if (existingSpan instanceof AgentSpan) {
      // return activateSpan((AgentSpan) existingSpan);
    }
    return null;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.This final HttpChannel channel, @Advice.Enter final AgentScope scope) {
    if (scope != null) {
      scope.close();
    }
  }
}
