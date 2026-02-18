package datadog.trace.instrumentation.reactivestreams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

/**
 * This instrumentation is responsible for capturing the context when {@link
 * Publisher#subscribe(Subscriber)} is called. The state is then stored and will be used to
 * eventually propagate on the downstream signals.
 */
public class PublisherInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "org.reactivestreams.Publisher";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("org.reactivestreams.Publisher"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, hasInterface(named("org.reactivestreams.Subscriber")))),
        getClass().getName() + "$PublisherSubscribeAdvice");
  }

  public static class PublisherSubscribeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onSubscribe(
        @Advice.This final Publisher self, @Advice.Argument(value = 0) final Subscriber s) {

      final Context context =
          InstrumentationContext.get(Publisher.class, Context.class).remove(self);
      final Context activeContext = Java8BytecodeBridge.getCurrentContext();
      if (s == null || (context == null && activeContext == null)) {
        return null;
      }
      final Context current =
          InstrumentationContext.get(Subscriber.class, Context.class)
              .putIfAbsent(s, context != null ? context : activeContext);
      if (current != null) {
        return current.attach();
      }

      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void afterSubscribe(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
