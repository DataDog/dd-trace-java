package datadog.trace.instrumentation.codeorigin;

import datadog.trace.bootstrap.debugger.DebuggerContext;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class EntrySpanOriginAdvice {

  @Advice.OnMethodEnter
  @SuppressWarnings("bytebuddy-exception-suppression")
  public static void onEnter(@Advice.Origin final Method method) {
    DebuggerContext.captureCodeOrigin(method, true);
  }
}
