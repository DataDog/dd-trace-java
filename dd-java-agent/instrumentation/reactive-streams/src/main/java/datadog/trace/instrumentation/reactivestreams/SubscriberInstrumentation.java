package datadog.trace.instrumentation.reactivestreams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
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
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.reactive.PublisherState;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * This instrumentation is responsible for propagating the publisher state to the subscriber and to
 * propagate the state on the lifecycle methods (onNext, onError, onComplete).
 */
@AutoService(InstrumenterModule.class)
public class SubscriberInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public SubscriberInstrumentation() {
    super("reactor-core");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("onNext")), getClass().getName() + "$OnNextAdvice");
    transformer.applyAdvice(
        isMethod().and(named("onError")), getClass().getName() + "$OnErrorAdvice");
    transformer.applyAdvice(
        isMethod().and(named("onComplete")), getClass().getName() + "$OnCompleteAdvice");
    transformer.applyAdvice(
        isMethod().and(named("onSubscribe")), getClass().getName() + "$OnSubscribeAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    ret.put("org.reactivestreams.Subscriber", PublisherState.class.getName());
    ret.put("org.reactivestreams.Publisher", PublisherState.class.getName());
    ret.put("org.reactivestreams.Subscription", "org.reactivestreams.Subscriber");
    return ret;
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.reactivestreams.Subscriber";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return HierarchyMatchers.implementsInterface(NameMatchers.named(hierarchyMarkerType()));
  }

  /** Propagates the span captured onSubscribe when the onNext method is called. */
  public static class OnNextAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.This final Subscriber self) {
      final PublisherState state =
          InstrumentationContext.get(Subscriber.class, PublisherState.class).get(self);
      final AgentSpan span = state != null ? state.getSubscriptionSpan() : null;
      System.err.println(self + " ONNEXT " + span);
      return span == null || span == activeSpan() ? null : AgentTracer.activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  /**
   * Propagates the span captured onSubscribe when the onError method is called. It also finishes
   * span attached to this subscriber if any.
   */
  public static class OnErrorAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This final Subscriber self, @Advice.Argument(0) Throwable t) {
      final PublisherState state =
          InstrumentationContext.get(Subscriber.class, PublisherState.class).get(self);
      final AgentSpan span = state != null ? state.getSubscriptionSpan() : null;
      if (state != null) {
        for (final AgentSpan partner : state.getPartnerSpans()) {
          partner.addThrowable(t);
          partner.finish();
        }
      }
      return span == null || span == activeSpan() ? null : AgentTracer.activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  /**
   * Propagates the span captured onSubscribe when the onComplete method is called. It also finishes
   * span attached to this subscriber if any.
   */
  public static class OnCompleteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.This final Subscriber self) {
      final PublisherState state =
          InstrumentationContext.get(Subscriber.class, PublisherState.class).get(self);
      final AgentSpan span = state != null ? state.getSubscriptionSpan() : null;
      if (state != null) {
        for (final AgentSpan partner : state.getPartnerSpans()) {
          partner.finish();
        }
      }
      System.err.println(self + " ONCOMPLETE " + span);
      return span == null || span == activeSpan() ? null : AgentTracer.activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  /**
   * Propagates the {@link PublisherState} captured when the Publisher subscribed. It captures the
   * span to propagate in a best effort way: takes in priority the one captured by the publisher if
   * any, otherwise it takes the active one if any. It also links the subscription to itself in
   * order to be able to handle the cancel advice
   */
  public static class OnSubscribeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This final Subscriber self, @Advice.Argument(0) final Subscription subscription) {
      InstrumentationContext.get(Subscription.class, Subscriber.class).put(subscription, self);
      PublisherState state =
          InstrumentationContext.get(Subscriber.class, PublisherState.class)
              .putIfAbsent(self, PublisherState::new);

      if (state.getSubscriptionSpan() == null) {
        state.withSubscriptionSpan(activeSpan());
        System.err.println("SUBSCRIBER " + self + " SUBSCRIBED (no state):" + activeSpan());
        return null;
      }
      System.err.println(
          "SUBSCRIBER " + self + " SUBSCRIBED (with state):" + state.getSubscriptionSpan());
      if (activeSpan() != state.getSubscriptionSpan()) {
        return activateSpan(state.getSubscriptionSpan());
      }
      return null;
    }
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void closeScope(@Advice.Enter final AgentScope scope) {
    if (scope != null) {
      scope.close();
    }
  }
}
