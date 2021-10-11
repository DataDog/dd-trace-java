package datadog.trace.instrumentation.cats.effect;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import net.bytebuddy.asm.Advice;

public class IOShiftTickAdvice {
  public static class Constructor {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterInit(@Advice.This Runnable zis) {
      AdviceUtils.capture(InstrumentationContext.get(Runnable.class, State.class), zis, true);
    }
  }
}
