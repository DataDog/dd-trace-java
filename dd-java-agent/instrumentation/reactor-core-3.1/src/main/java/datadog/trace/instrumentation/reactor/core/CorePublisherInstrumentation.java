package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
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
        @Advice.This final Publisher<?> self,
        @Advice.Argument(0) final CoreSubscriber<?> subscriber) {
      // Hands the explicit context recorded for a context-writing subscriber to the publisher store
      // (for the reactive-streams hand-off) and attaches it. The subscriber wrapping for
      // context-reading operators lives in ContextReadingPublisherInstrumentation.
      return ReactorContextBridge.captureOnSubscribe(
          self,
          subscriber,
          InstrumentationContext.get(Publisher.class, Context.class),
          InstrumentationContext.get(Subscriber.class, Context.class));
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
