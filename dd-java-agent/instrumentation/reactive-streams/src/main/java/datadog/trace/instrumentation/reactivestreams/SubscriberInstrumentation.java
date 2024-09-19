package datadog.trace.instrumentation.reactivestreams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
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

  public static class OnNextAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.This final Subscriber self) {
      final PublisherState state =
          InstrumentationContext.get(Subscriber.class, PublisherState.class).get(self);
      final AgentSpan span = state != null ? state.getSubscriptionSpan() : null;
      return span == null ? null : AgentTracer.activateSpan(span, true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

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
      return span == null ? null : AgentTracer.activateSpan(span, true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

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
      return span == null ? null : AgentTracer.activateSpan(span, true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  public static class OnSubscribeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(
        @Advice.This final Subscriber self, @Advice.Argument(0) final Subscription subscription) {
      InstrumentationContext.get(Subscription.class, Subscriber.class).put(subscription, self);
    }
  }
}
