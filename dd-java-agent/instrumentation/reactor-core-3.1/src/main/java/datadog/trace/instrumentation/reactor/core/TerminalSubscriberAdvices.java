package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Hooks;

public class TerminalSubscriberAdvices {
  public static class OnSubscribeAdvice {
    @Advice.OnMethodEnter
    public static void onSubscribe(@Advice.This final Subscriber thiz) {
      AgentSpan span = AgentTracer.activeSpan();
      InstrumentationContext.get(Subscriber.class, AgentSpan.class)
          .put(thiz, span == null ? noopSpan() : span);
    }

    public static void muzzleCheck() {
      Hooks.resetOnEachOperator();
    }
  }

  public static class OnNextAndCompleteAndErrorAdvice {
    @Advice.OnMethodEnter
    public static AgentScope onMethod(@Advice.This final Subscriber thiz) {
      final AgentSpan span =
          InstrumentationContext.get(Subscriber.class, AgentSpan.class).get(thiz);
      return span == null ? null : AgentTracer.activateSpan(span, true);
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
