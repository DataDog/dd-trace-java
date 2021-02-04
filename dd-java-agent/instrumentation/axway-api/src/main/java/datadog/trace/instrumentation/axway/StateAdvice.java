package datadog.trace.instrumentation.axway;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.AXWAY_TRY_TRAMSACTION;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.DECORATE;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.HOST;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.PORT;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.setTagFromField;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import net.bytebuddy.asm.Advice;

public class StateAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.This final Object stateInstance) {
    final AgentSpan span = startSpan(AXWAY_TRY_TRAMSACTION);
    final AgentScope scope = activateSpan(span);
    span.setMeasured(true);
    // manually DECORATE.onRequest(span, stateInstance) :
    setTagFromField(span, Tags.HTTP_METHOD, stateInstance, "verb");
    setTagFromField(span, Tags.HTTP_URL, stateInstance, "uri");
    setTagFromField(span, Tags.PEER_HOSTNAME, stateInstance, HOST);
    setTagFromField(span, Tags.PEER_PORT, stateInstance, PORT);
    DECORATE.afterStart(span);
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    final AgentSpan span = scope.span();
    try {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
    } finally {
      scope.close();
      span.finish();
    }
  }
}
