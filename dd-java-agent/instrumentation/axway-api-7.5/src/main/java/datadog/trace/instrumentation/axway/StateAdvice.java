package datadog.trace.instrumentation.axway;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.AXWAY_TRY_TRANSACTION;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.DECORATE;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

/**
 * Axway apigateway server gathers responses from 1 or more services, aggregates them and sends
 * response(s) to client(s). Apigateway is just reverse proxy. com.vordel.circuit.net.State class
 * represents connection(s) and "state" of communication to one or more of the services from which
 * axway apigateway needs to get reply to prepare aggregates response to customer. This
 * instrumentation intends to see to which services apigateway goes to prepare it response.
 */
public class StateAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.This final Object stateInstance) {
    final AgentSpan span = startSpan(AXWAY_TRY_TRANSACTION);
    final AgentScope scope = activateSpan(span);
    span.setMeasured(true);
    DECORATE.onTransaction(span, stateInstance);
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
    final Context context = scope.context();
    try {
      if (throwable != null) {
        DECORATE.onError(span, throwable);
      }
      DECORATE.beforeFinish(context);
    } finally {
      scope.close();
      span.finish();
    }
  }
}
