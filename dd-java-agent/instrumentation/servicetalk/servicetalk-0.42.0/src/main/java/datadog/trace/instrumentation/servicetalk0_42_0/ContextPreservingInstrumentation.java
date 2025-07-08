package datadog.trace.instrumentation.servicetalk0_42_0;

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
  }
}
