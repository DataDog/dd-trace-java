package datadog.trace.instrumentation.gson;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.gson.GsonDecorator.DECORATE;
import static datadog.trace.instrumentation.gson.GsonDecorator.GSON_FROM_JSON;

import com.google.gson.Gson;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Type;
import net.bytebuddy.asm.Advice;

public class GsonFromJsonAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.Argument(0) final Object source, @Advice.Argument(1) final Object typeArg) {
    final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Gson.class);
    if (callDepth > 0) {
      return null;
    }

    final AgentSpan span = startSpan("gson", GSON_FROM_JSON);
    DECORATE.afterStart(span);

    if (typeArg instanceof Class) {
      span.setTag("json.target.type", ((Class<?>) typeArg).getName());
    } else if (typeArg instanceof Type) {
      span.setTag("json.target.type", typeArg.toString());
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
