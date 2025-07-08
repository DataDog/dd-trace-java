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
}
