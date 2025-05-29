package datadog.trace.instrumentation.axway;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.DECORATE;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.SERVER_TRANSACTION_CLASS;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import net.bytebuddy.asm.Advice;

public class HTTPPluginAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Argument(value = 2) final Object serverTransaction) {
    final AgentSpan span = startSpan(DECORATE.spanName()).setMeasured(true);
    DECORATE.afterStart(span);
    // serverTransaction is like request + connection in one object:
    DECORATE.onRequest(
        span, serverTransaction, serverTransaction, (AgentSpanContext.Extracted) null);
    final AgentScope scope = activateSpan(span);
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final AgentScope scope,
      @Advice.Argument(value = 2) final Object serverTransaction,
      @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    final AgentSpan span = scope.span();
    try {
      if (null != serverTransaction) {
        // manual DECORATE.onResponse(span, serverTransaction):
        // TODO: It doesn't work. Rewriting of InstrumentationContext.get fails here, because both
        // arguments should be
        //  class-literals (not runtime Class object) to make FieldBackedContextRequestRewriter
        // work.
        int respCode =
            InstrumentationContext.get(SERVER_TRANSACTION_CLASS, int.class).get(serverTransaction);
        span.setHttpStatusCode(respCode);
      }
      if (throwable != null) {
        DECORATE.onError(span, throwable);
      }
      DECORATE.beforeFinish(span);
    } finally {
      scope.close();
      span.finish();
    }
  }
}
