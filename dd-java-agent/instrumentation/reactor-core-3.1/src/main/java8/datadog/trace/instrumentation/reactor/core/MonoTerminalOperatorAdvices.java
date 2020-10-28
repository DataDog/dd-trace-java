package datadog.trace.instrumentation.reactor.core;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Hooks;

public class MonoTerminalOperatorAdvices {
  public static class OnSubscribeAdvice {
    @Advice.OnMethodEnter
    public static void onSubscribe(@Advice.This final Subscriber thiz) {
      AgentSpan span = AgentTracer.activeSpan();
      if (span == null) {
        span = AgentTracer.noopSpan();
      }

      final ContextStore<Subscriber, AgentSpan> contextStore =
          InstrumentationContext.get(Subscriber.class, AgentSpan.class);
      contextStore.put(thiz, span);
    }

    public static void muzzleCheck() {
      Hooks.resetOnEachOperator();
    }
  }

  public static class OnNextAndCompleteAndErrorAdvice {
    @Advice.OnMethodEnter
    public static AgentScope onMethod(@Advice.This final Subscriber thiz) {
      final ContextStore<Subscriber, AgentSpan> contextStore =
          InstrumentationContext.get(Subscriber.class, AgentSpan.class);
      final AgentSpan span = contextStore.get(thiz);

      if (span == null) {
        return null;
      }

      return AgentTracer.activateSpan(span, true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }

    public static void muzzleCheck() {
      Hooks.resetOnEachOperator();
    }
  }
}
