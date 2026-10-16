package datadog.trace.instrumentation.gson;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.gson.GsonDecorator.DECORATE;
import static datadog.trace.instrumentation.gson.GsonDecorator.GSON_TO_JSON;

import com.google.gson.Gson;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public class GsonToJsonAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Argument(0) final Object src) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Gson.class);
    if (callDepth > 0) {
      return null;
    }

    final AgentSpan span = startSpan("gson", GSON_TO_JSON);
    DECORATE.afterStart(span);

    if (src != null) {
      span.setTag("json.source.type", src.getClass().getName());
    }

    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }

    CallDepthThreadLocalMap.reset(Gson.class);

    final AgentSpan span = scope.span();
    if (throwable != null) {
      DECORATE.onError(span, throwable);
    }
    DECORATE.beforeFinish(span);
    scope.close();
    span.finish();
  }
}
