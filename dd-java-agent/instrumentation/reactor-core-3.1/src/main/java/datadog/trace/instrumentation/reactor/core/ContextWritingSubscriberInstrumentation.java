package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Subscriber;
import reactor.core.CoreSubscriber;

/**
 * Tailored instrumentation for Reactor's context-writing subscribers (the inner subscribers created
 * by {@code contextWrite}/{@code subscriberContext} operators). Matching them by exact type removes
 * the per-signal {@code instanceof} chain that the generic {@code CoreSubscriberInstrumentation}
 * used to run on every {@link CoreSubscriber}.
 *
 * <p>The explicit Datadog {@link Context} carried by these subscribers is fixed at construction, so
 * it is read once (constructor advice) and stored; signal advice then only does a context-store
 * lookup.
 */
public class ContextWritingSubscriberInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "reactor.core.publisher.FluxContextWrite$ContextWriteSubscriber",
      "reactor.core.publisher.FluxContextStart$ContextStartSubscriber",
      "reactor.core.publisher.FluxContextWriteRestoringThreadLocals"
          + "$ContextWriteRestoringThreadLocalsSubscriber",
      "reactor.core.publisher.FluxContextWriteRestoringThreadLocalsFuseable"
          + "$FuseableContextWriteRestoringThreadLocalsSubscriber",
      "reactor.core.publisher.MonoContextWriteRestoringThreadLocals"
          + "$ContextWriteRestoringThreadLocalsSubscriber",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureContextAdvice");
    transformer.applyAdvice(
        namedOneOf("onNext", "onComplete", "onError"),
        getClass().getName() + "$ActivateContextAdvice");
  }

  public static class CaptureContextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstructed(@Advice.This final CoreSubscriber<?> self) {
      ReactorContextBridge.captureSubscriberContext(
          self, InstrumentationContext.get(Subscriber.class, Context.class));
    }
  }

  public static class ActivateContextAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope before(@Advice.This final CoreSubscriber<?> self) {
      return ReactorContextBridge.activateStoredContext(
          self, InstrumentationContext.get(Subscriber.class, Context.class));
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
