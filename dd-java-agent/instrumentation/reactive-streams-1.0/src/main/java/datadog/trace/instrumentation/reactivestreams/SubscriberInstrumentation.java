package datadog.trace.instrumentation.reactivestreams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Subscriber;

/**
 * This instrumentation is responsible for propagating the state on the downstream signals (onNext,
 * onError, onComplete).
 */
@AutoService(InstrumenterModule.class)
public class SubscriberInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public SubscriberInstrumentation() {
    super("reactive-streams", "reactive-streams-1");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(namedOneOf("onNext", "onError")),
        getClass().getName() + "$SubscriberDownStreamAdvice");
    transformer.applyAdvice(
        isMethod().and(named("onComplete")), getClass().getName() + "$SubscriberCompleteAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("org.reactivestreams.Subscriber", AgentSpan.class.getName());
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
   * scope propagate downstream if any. If missing, we'll use the one captured on subscribe.
   */
  public static class SubscriberDownStreamAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.This final Subscriber self) {
      if (activeSpan() != null) {
        return null;
      }
      final AgentSpan span =
          InstrumentationContext.get(Subscriber.class, AgentSpan.class).get(self);
      return span == null ? null : activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  /**
   * Propagates the span captured onSubscribe when the onComplete method is called. We do not let to
   * propagate the active span if different from the span captured on subscribe because we need to
   * ensure that late subscriptions that kicks onComplete have the right context.
   */
  public static class SubscriberCompleteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.This final Subscriber self) {
      final AgentSpan span =
          InstrumentationContext.get(Subscriber.class, AgentSpan.class).get(self);
      return span == null ? null : activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
