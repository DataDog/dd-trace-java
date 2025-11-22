package com.datadog.debugger.origin;

import static datadog.trace.bootstrap.debugger.DebuggerContext.captureCodeOrigin;
import static datadog.trace.bootstrap.debugger.DebuggerContext.marker;

import net.bytebuddy.asm.Advice;

public class CodeOriginTestAdvice {

  @Advice.OnMethodEnter
  @SuppressWarnings("bytebuddy-exception-suppression")
  public static void onEnter(
      @Advice.Origin("#t") String typeName,
      @Advice.Origin("#m") String methodName,
      @Advice.Origin("#d") String descriptor) {
    marker();
    captureCodeOrigin(typeName, methodName, descriptor, true);
  }
}
