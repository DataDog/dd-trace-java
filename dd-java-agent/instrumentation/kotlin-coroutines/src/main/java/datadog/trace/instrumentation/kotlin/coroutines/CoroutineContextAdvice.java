package datadog.trace.instrumentation.kotlin.coroutines;

import datadog.trace.core.ScopeStackCoroutineContextHelper;
import kotlin.coroutines.CoroutineContext;
import net.bytebuddy.asm.Advice;

public class CoroutineContextAdvice {
  @Advice.OnMethodEnter
  public static void enter(
      @Advice.Argument(value = 1, readOnly = false) CoroutineContext coroutineContext) {
    if (coroutineContext != null) {
      coroutineContext = ScopeStackCoroutineContextHelper.addScopeStackContext(coroutineContext);
    }
  }
}
