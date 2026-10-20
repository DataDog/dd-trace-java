package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import kotlin.coroutines.CoroutineContext;
import net.bytebuddy.asm.Advice;

/** Adds {@link DatadogThreadContextElement} to every coroutine context. */
public class CoroutineContextInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "kotlinx.coroutines.CoroutineContextKt";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("newCoroutineContext")
            .and(takesArgument(0, named("kotlinx.coroutines.CoroutineScope")))
            .and(takesArgument(1, named("kotlin.coroutines.CoroutineContext"))),
        this.getClass().getName() + "$ContextAdvice");
    // Isolates context merges used by withContext since coroutines 1.6.1 and flowOn since 1.7.2.
    transformer.applyAdvice(
        named("newCoroutineContext")
            .and(takesArgument(0, named("kotlin.coroutines.CoroutineContext")))
            .and(takesArgument(1, named("kotlin.coroutines.CoroutineContext"))),
        this.getClass().getName() + "$ContextMergeAdvice");
  }

  public static class ContextAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 1, readOnly = false) CoroutineContext coroutineContext) {
      if (coroutineContext != null) {
        coroutineContext = DatadogThreadContextElement.addDatadogElement(coroutineContext);
      }
    }
  }

  public static class ContextMergeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.Argument(0) CoroutineContext original,
        @Advice.Return(readOnly = false) CoroutineContext merged) {
      merged = DatadogThreadContextElement.forContextMerge(merged, original);
    }
  }
}
