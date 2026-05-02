package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

/**
 * This instrumentation is responsible for transferring the {@link Context} when subscription
 * optimization are made. In particular reactor's OptimizableOperators can do subscription via a
 * loop call instead of recursion.
 */
public class OptimizableOperatorInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "reactor.core.publisher.OptimizableOperator";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
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
        @Advice.This final Publisher self,
        @Advice.Argument(0) final Subscriber arg,
        @Advice.Return final Subscriber s) {
      if (s == null || arg == null) {
        return;
      }
      Context context = InstrumentationContext.get(Publisher.class, Context.class).get(self);
      if (context == null) {
        context = InstrumentationContext.get(Subscriber.class, Context.class).get(arg);
      }
      if (context != null) {
        InstrumentationContext.get(Subscriber.class, Context.class).putIfAbsent(s, context);
      }
    }
  }
}
