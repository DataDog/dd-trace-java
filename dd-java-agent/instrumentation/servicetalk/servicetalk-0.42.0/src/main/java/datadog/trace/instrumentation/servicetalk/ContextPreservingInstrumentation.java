package datadog.trace.instrumentation.servicetalk;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.servicetalk.context.api.ContextMap;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ContextPreservingInstrumentation extends ServiceTalkInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.servicetalk.concurrent.api.ContextPreservingBiConsumer",
      "io.servicetalk.concurrent.api.ContextPreservingBiFunction",
      "io.servicetalk.concurrent.api.ContextPreservingCallable",
      "io.servicetalk.concurrent.api.ContextPreservingCancellable",
      "io.servicetalk.concurrent.api.ContextPreservingCompletableSubscriber",
      "io.servicetalk.concurrent.api.ContextPreservingConsumer",
      "io.servicetalk.concurrent.api.ContextPreservingFunction",
      "io.servicetalk.concurrent.api.ContextPreservingRunnable",
      "io.servicetalk.concurrent.api.ContextPreservingSingleSubscriber",
      "io.servicetalk.concurrent.api.ContextPreservingSubscriber",
      "io.servicetalk.concurrent.api.ContextPreservingSubscription",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf(
            "accept",
            "apply",
            "call",
            "cancel",
            "onComplete",
            "onError",
            "onSuccess",
            "request",
            "onNext",
            "onSubscribe",
            "run"),
        getClass().getName() + "$Wrapper");
  }

  public static final class Wrapper {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(@Advice.FieldValue("saved") final ContextMap contextMap) {
      AgentSpan parent =
          InstrumentationContext.get(ContextMap.class, AgentSpan.class).get(contextMap);
      if (parent != null) {
        return AgentTracer.activateSpan(parent);
      }
      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope agentScope) {
      if (agentScope != null) {
        agentScope.close();
      }
    }

    // TODO muzzle to be applied only for older versions < 0.42.56
    public static void muzzleCheck() {
      // TODO how to disable this instrumentation for 0.42.56+
      // because otherwise it fails to instrument b/o missing "saved" fields
      // Also ContextMapInstrumentation shouldn't be applied to 0.42.56+

      // We need a class or method that has been deleted in 0.42.56 but was in 0.42.0+
      // But there is no such thing comparing 0.42.55 and 0.42.56 of servicetalk-concurrent-api

      // Can't check io.servicetalk.concurrent.api.ContextPreservingCallable.saved
      // b/o the class is package private and the field is private

    }
  }
}
