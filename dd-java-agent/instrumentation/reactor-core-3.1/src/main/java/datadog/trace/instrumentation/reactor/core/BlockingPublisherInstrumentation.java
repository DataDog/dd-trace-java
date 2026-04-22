package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;

/**
 * This instrumentation is responsible for capturing the right state when Mono or Flux block*
 * methods are called. This because the mechanism they handle this differs a bit of the standard
 * {@link Publisher#subscribe}
 */
public class BlockingPublisherInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "reactor.core.publisher.Mono";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return hasSuperType(namedOneOf("reactor.core.publisher.Mono", "reactor.core.publisher.Flux"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(nameStartsWith("block")), getClass().getName() + "$BlockingAdvice");
  }

  public static class BlockingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope before(@Advice.This final Publisher self) {
      final Context context = InstrumentationContext.get(Publisher.class, Context.class).get(self);
      if (context == null) {
        return null;
      }
      return context.attach();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
