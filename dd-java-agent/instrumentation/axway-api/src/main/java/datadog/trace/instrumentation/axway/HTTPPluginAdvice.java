package datadog.trace.instrumentation.axway;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getRootContext;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.DECORATE;
import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.SERVER_TRANSACTION_CLASS;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public class HTTPPluginAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope onEnter(@Advice.Argument(value = 2) final Object serverTransaction) {
    final AgentSpan span = startSpan("axway-api", DECORATE.spanName()).setMeasured(true);
    DECORATE.afterStart(span);
    // serverTransaction is like request + connection in one object:
    DECORATE.onRequest(span, serverTransaction, serverTransaction, getRootContext());
    return getCurrentContext().with(span).attach();
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final ContextScope scope,
      @Advice.Argument(value = 2) final Object serverTransaction,
      @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    final Context context = scope.context();
    final AgentSpan span = fromContext(context);
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
      DECORATE.beforeFinish(context);
    } finally {
      scope.close();
      span.finish();
    }
  }
}
