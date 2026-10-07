package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import kotlin.coroutines.Continuation;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Snapshots context before Kotlin dispatches context changes or publishes suspension decisions.
 * Custom low-level suspension primitives can bypass these hooks.
 */
public class SuspensionInstrumentation
    implements Instrumenter.ForKnownTypes,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {
  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "kotlinx.coroutines.BuildersKt__Builders_commonKt",
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
    return named("kotlinx.coroutines.BuildersKt__Builders_commonKt")
        .or(hasSuperType(named("kotlin.coroutines.Continuation")));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("withContext")
            .and(isStatic())
            .and(takesArguments(3))
            .and(takesArgument(2, named("kotlin.coroutines.Continuation"))),
        SuspensionInstrumentation.class.getName() + "$ContextChangeAdvice");
    transformer.applyAdvice(
        named("getResult")
            .or(named("getResult$kotlinx_coroutines_core"))
            .or(named("getOrThrow"))
            .and(takesNoArguments()),
        SuspensionInstrumentation.class.getName() + "$SuspensionAdvice");
    transformer.applyAdvice(
        nameStartsWith("dispatchYield"),
        SuspensionInstrumentation.class.getName() + "$SuspensionAdvice");
  }

  public static class ContextChangeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object beforeContextChange(@Advice.Argument(2) Continuation<?> continuation) {
      return DatadogThreadContextElement.beforeContextChange(continuation);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterContextChange(
        @Advice.Argument(2) Continuation<?> continuation,
        @Advice.Enter Object original,
        @Advice.Return Object result) {
      if (result != getCOROUTINE_SUSPENDED()) {
        DatadogThreadContextElement.afterContextChange(continuation, original);
      }
    }
  }

  public static class SuspensionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeSuspension(@Advice.This Continuation<?> continuation) {
      DatadogThreadContextElement.beforeSuspension(continuation);
    }
  }
}
