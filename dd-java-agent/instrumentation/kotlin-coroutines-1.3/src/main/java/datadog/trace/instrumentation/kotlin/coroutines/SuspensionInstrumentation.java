package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import kotlin.coroutines.Continuation;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Snapshots context before Kotlin publishes standard suspension or yield decisions. Custom
 * low-level suspension primitives can bypass these hooks.
 */
public class SuspensionInstrumentation
    implements Instrumenter.ForKnownTypes,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {
  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "kotlinx.coroutines.CancellableContinuationImpl",
      "kotlin.coroutines.SafeContinuation",
      "kotlinx.coroutines.DispatchedCoroutine",
      "kotlinx.coroutines.selects.SelectBuilderImpl",
      "kotlinx.coroutines.DispatchedContinuation",
      "kotlinx.coroutines.internal.DispatchedContinuation"
    };
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return hasSuperType(named("kotlin.coroutines.Continuation"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getResult").or(named("getOrThrow")).and(takesNoArguments()),
        SuspensionInstrumentation.class.getName() + "$SuspensionAdvice");
    transformer.applyAdvice(
        nameStartsWith("dispatchYield"),
        SuspensionInstrumentation.class.getName() + "$SuspensionAdvice");
  }

  public static class SuspensionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeSuspension(@Advice.This Continuation<?> continuation) {
      DatadogThreadContextElement.beforeSuspension(continuation);
    }
  }
}
