package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.reactive.PublisherState;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

/**
 * This instrumentation is responsible for transferring the {@link PublisherState} when subscription
 * optimization are made. In particular reactor's OptimizableOperators can do subscription via a
 * loop call instead of recursion.
 */
@AutoService(InstrumenterModule.class)
public class OptimizableOperatorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public OptimizableOperatorInstrumentation() {
    super("reactor-core");
  }

  @Override
  public String hierarchyMarkerType() {
    return "reactor.core.publisher.OptimizableOperator";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("org.reactivestreams.Publisher"));
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    ret.put("org.reactivestreams.Subscriber", PublisherState.class.getName());
    ret.put("org.reactivestreams.Publisher", PublisherState.class.getName());
    return ret;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("subscribeOrReturn"))
            .and(takesArguments(1))
            .and(returns(hasInterface(named("org.reactivestreams.Subscriber")))),
        getClass().getName() + "$PublisherSubscribeAdvice");
  }

  public static class PublisherSubscribeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onSubscribe(
        @Advice.This final Publisher self, @Advice.Return final Subscriber s) {
      if (s == null) {
        return;
      }
      PublisherState publisherState =
          InstrumentationContext.get(Publisher.class, PublisherState.class).get(self);
      if (publisherState != null) {
        InstrumentationContext.get(Subscriber.class, PublisherState.class)
            .putIfAbsent(s, publisherState)
            .getPartnerSpans()
            .addAll(publisherState.getPartnerSpans());
      }
    }
  }
}
