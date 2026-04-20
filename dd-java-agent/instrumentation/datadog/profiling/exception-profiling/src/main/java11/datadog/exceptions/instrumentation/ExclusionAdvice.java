package datadog.exceptions.instrumentation;

import datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionProfiling;
import net.bytebuddy.asm.Advice;

public class ExclusionAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter() {
    ExceptionProfiling.Exclusion.enter();
  }

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.This final Object t) {
    ExceptionProfiling.Exclusion.exit();
  }
}
