package datadog.trace.instrumentation.kotlin.coroutines;

import kotlin.coroutines.CoroutineContext;
import net.bytebuddy.asm.Advice;

public class CoroutineContextAdvice {

  // This is applied last to ensure that we have a Job attached before our context is added,
  // so we can register the on completion callback to clean up our resources
  @Advice.OnMethodExit
  public static void exit(@Advice.Return(readOnly = false) CoroutineContext coroutineContext) {
    if (coroutineContext != null) {
      coroutineContext = coroutineContext.plus(new ScopeStateCoroutineContext(coroutineContext));
    }
  }
}
