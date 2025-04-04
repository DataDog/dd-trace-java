package com.datadog.debugger.origin;

import datadog.trace.bootstrap.debugger.DebuggerContext;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class CodeOriginTestAdvice {

  @Advice.OnMethodEnter
  @SuppressWarnings("bytebuddy-exception-suppression")
  public static void onEnter(@Advice.Origin final Method method) {
    System.out.println("****** CodeOriginTestAdvice.onEnter method = " + method);
    DebuggerContext.marker();
    DebuggerContext.captureCodeOrigin(method, true);
  }
}
