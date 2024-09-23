package datadog.trace.instrumentation.reactivestreams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.reactive.PublisherState;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * This instrumentation is making sure that partner spans that are sticky to a subscriber are closed
 * when the subscription is canceled.
 */
@AutoService(InstrumenterModule.class)
public class SubscriptionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public SubscriptionInstrumentation() {
    super("reactor-core");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("cancel")), getClass().getName() + "$CancelAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    ret.put("org.reactivestreams.Subscriber", PublisherState.class.getName());
    ret.put("org.reactivestreams.Subscription", "org.reactivestreams.Subscriber");
    return ret;
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.reactivestreams.Subscription";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return HierarchyMatchers.implementsInterface(NameMatchers.named(hierarchyMarkerType()));
  }

  public static class CancelAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onMethod(@Advice.This final Subscription thiz) {
      final Subscriber subscriber =
          InstrumentationContext.get(Subscription.class, Subscriber.class).remove(thiz);
      if (subscriber == null) {
        return;
      }
      final PublisherState state =
          InstrumentationContext.get(Subscriber.class, PublisherState.class).get(subscriber);
      if (state == null || state.getPartnerSpans().isEmpty()) {
        return;
      }
      for (AgentSpan span : state.getPartnerSpans()) {
        span.finish();
      }
      // to remove the risk to close them more than once
      state.getPartnerSpans().clear();
    }
  }
}
