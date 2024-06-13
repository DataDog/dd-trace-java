package datadog.trace.instrumentation.servicetalk;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.servicetalk.context.api.ContextMap;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ContextPreservingInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes {

  public ContextPreservingInstrumentation() {
    super("servicetalk", "servicetalk-concurrent");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.servicetalk.concurrent.api.ContextPreservingBiConsumer", // accept
      "io.servicetalk.concurrent.api.ContextPreservingBiFunction", // apply
      "io.servicetalk.concurrent.api.ContextPreservingCallable", // call
      "io.servicetalk.concurrent.api.ContextPreservingCancellable", // cancel TODO
      "io.servicetalk.concurrent.api.ContextPreservingCompletableSubscriber", // onSubscribe,
      // onComplete, onError
      // TODO
      "io.servicetalk.concurrent.api.ContextPreservingConsumer", // accept
      "io.servicetalk.concurrent.api.ContextPreservingFunction", // apply
      "io.servicetalk.concurrent.api.ContextPreservingRunnable", // run
      "io.servicetalk.concurrent.api.ContextPreservingSingleSubscriber", // onSubscribe, onSuccess,
      // onError TODO
      "io.servicetalk.concurrent.api.ContextPreservingSubscriber", // onSubscribe, onNext, onError,
      // onComplete TODO
      "io.servicetalk.concurrent.api.ContextPreservingSubscription", // request, cancel TODO

      //        "io.servicetalk.concurrent.api.ContextPreservingCancellableCompletableSubscriber",
      //        "io.servicetalk.concurrent.api.ContextPreservingCompletableFuture",
      //
      // "io.servicetalk.concurrent.api.ContextPreservingCompletableSubscriberAndCancellable",
      //        "io.servicetalk.concurrent.api.ContextPreservingExecutor",
      //        "io.servicetalk.concurrent.api.ContextPreservingExecutorService",
      //        "io.servicetalk.concurrent.api.ContextPreservingScheduledExecutorService",
      //        "io.servicetalk.concurrent.api.ContextPreservingSingleSubscriberAndCancellable",
      //        "io.servicetalk.concurrent.api.ContextPreservingStExecutor",
      //        "io.servicetalk.concurrent.api.ContextPreservingSubscriberAndSubscription",
      //        "io.servicetalk.concurrent.api.ContextPreservingSubscriptionSubscriber",

    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.servicetalk.context.api.ContextMap", AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(1, named("io.servicetalk.context.api.ContextMap"))),
        getClass().getName() + "$Construct");

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
            "onSubscribe", // TODO
            "run"),
        getClass().getName() + "$Wrapper");
  }

  public static final class Construct {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enter(@Advice.Origin Class<?> clazz) {
      int level = CallDepthThreadLocalMap.incrementCallDepth(clazz);
      return level == 0;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final boolean topLevel,
        @Advice.Origin Class<?> clazz,
        @Advice.FieldValue("saved") ContextMap saved) {
      if (!topLevel) {
        return;
      }
      CallDepthThreadLocalMap.reset(clazz);

      AgentSpan activeSpan = AgentTracer.activeSpan();
      if (activeSpan != null) {
        InstrumentationContext.get(ContextMap.class, AgentSpan.class).put(saved, activeSpan);
      }
    }
  }

  public static final class Wrapper {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(@Advice.FieldValue("saved") final ContextMap contextMap
        //        , @Advice.Origin Class<?> clazz
        ) {
      ContextStore<ContextMap, AgentSpan> contextStore =
          InstrumentationContext.get(ContextMap.class, AgentSpan.class);

      AgentSpan parent = contextStore.get(contextMap);
      if (parent != null) {
        return AgentTracer.activateSpan(parent);
        //        AgentSpan span = AgentTracer.startSpan("servicetalk", clazz.getSimpleName(),
        // parent.context());
        //        return AgentTracer.activateSpan(span);
      }

      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope agentScope) {
      if (agentScope != null) {
        AgentSpan span = agentScope.span();
        span.finish();
        agentScope.close();
      }
    }
  }
}
