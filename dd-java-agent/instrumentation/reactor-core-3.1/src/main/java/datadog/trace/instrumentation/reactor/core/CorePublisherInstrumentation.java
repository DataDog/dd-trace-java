package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.reactor.core.ContextSpanHelper.extractContextFromSubscriberContext;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.CoreSubscriber;

public class CorePublisherInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "reactor.core.CoreSubscriber";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("reactor.core.CorePublisher")) // from 3.1.7
        .or(
            hasSuperType(
                namedOneOf(
                    "reactor.core.publisher.Mono", "reactor.core.publisher.Flux"))); // < 3.1.7
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("subscribe")
            .and(not(isStatic()))
            .and(takesArguments(1))
            .and(takesArgument(0, named("reactor.core.CoreSubscriber"))),
        getClass().getName() + "$PropagateContextSpanOnSubscribe");
  }

  public static class PropagateContextSpanOnSubscribe {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope before(
        @Advice.This Publisher<?> self, @Advice.Argument(0) final CoreSubscriber<?> subscriber) {
      final Context context = extractContextFromSubscriberContext(subscriber);

      if (context != null) {
        // we force storing the context state linked to publisher and subscriber to the one
        // explicitly
        // present in the reactor context so that, if PublisherInstrumentation is kicking in after
        // this
        // advice, it won't override that active context
        InstrumentationContext.get(Publisher.class, Context.class).put(self, context);
        InstrumentationContext.get(Subscriber.class, Context.class).put(subscriber, context);
        return context.attach();
      }
      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
