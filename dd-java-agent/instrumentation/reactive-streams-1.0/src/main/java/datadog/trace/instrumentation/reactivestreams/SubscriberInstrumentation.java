package datadog.trace.instrumentation.reactivestreams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Subscriber;

/**
 * This instrumentation is responsible for propagating the state on the downstream signals (onNext,
 * onError, onComplete).
 */
public class SubscriberInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(namedOneOf("onNext", "onError")),
        getClass().getName() + "$SubscriberDownStreamAdvice");
    transformer.applyAdvice(
        isMethod().and(named("onComplete")), getClass().getName() + "$SubscriberCompleteAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.reactivestreams.Subscriber";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return HierarchyMatchers.implementsInterface(NameMatchers.named(hierarchyMarkerType()));
  }

  /**
   * This advice propagate the downstream signals onNext and onError. The context on reactor is
   * propagating bottom up, but we can have processing pipelines that wants to propagate the state
   * downstream (i.e. state coming from the source). For this reason we allow to let the active
   * context propagate downstream if any. If missing, we'll use the one captured on subscribe.
   */
  public static class SubscriberDownStreamAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope before(@Advice.This final Subscriber self) {
      final Context currentContext = Java8BytecodeBridge.getCurrentContext();
      if (currentContext != null && currentContext != Java8BytecodeBridge.getRootContext()) {
        return null;
      }
      final Context context = InstrumentationContext.get(Subscriber.class, Context.class).get(self);
      return context == null ? null : context.attach();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  /**
   * Propagates the context captured onSubscribe when the onComplete method is called. We do not let
   * to propagate the active context if different from the context captured on subscribe because we
   * need to ensure that late subscriptions that kicks onComplete have the right context.
   */
  public static class SubscriberCompleteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope before(@Advice.This final Subscriber self) {
      final Context context = InstrumentationContext.get(Subscriber.class, Context.class).get(self);
      return context == null ? null : context.attach();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
