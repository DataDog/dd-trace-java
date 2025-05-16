package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import kotlinx.coroutines.AbstractCoroutine;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Captures the Datadog context when lazy coroutines start. */
public class LazyCoroutineInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "kotlinx.coroutines.AbstractCoroutine";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("onStart")).and(takesNoArguments()),
        LazyCoroutineInstrumentation.class.getName() + "$OnStartAdvice");
  }

  public static class OnStartAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onStart(@Advice.This AbstractCoroutine<?> coroutine) {
      DatadogThreadContextElement.captureDatadogContext(coroutine);
    }
  }
}
