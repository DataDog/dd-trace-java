package datadog.trace.instrumentation.reactivestreams;

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
 * This instrumentation is responsible for propagating the state on the lifecycle methods (onNext,
 * onError, onComplete).
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
        isMethod().and(namedOneOf("onNext", "onError", "onComplete")),
        getClass().getName() + "$SubscriberScopeAdvice");
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
   * Propagates the span captured onSubscribe when the onNext, onError or onComplete method is
   * called.
   */
  public static class SubscriberScopeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.This final Subscriber self) {
      final AgentSpan span =
          InstrumentationContext.get(Subscriber.class, AgentSpan.class).get(self);
      return span == null || span == activeSpan() ? null : activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
