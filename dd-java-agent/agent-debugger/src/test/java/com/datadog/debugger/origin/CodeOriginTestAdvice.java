package com.datadog.debugger.origin;

import static datadog.trace.bootstrap.debugger.DebuggerContext.*;
import static datadog.trace.bootstrap.debugger.DebuggerContext.marker;

import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class CodeOriginTestAdvice {

  @Advice.OnMethodEnter
  @SuppressWarnings("bytebuddy-exception-suppression")
  public static void onEnter(@Advice.Origin final Method method) {
    marker();
    captureCodeOrigin(method, true);
  }
}
