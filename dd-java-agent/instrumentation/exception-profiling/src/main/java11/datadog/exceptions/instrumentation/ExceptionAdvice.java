package datadog.exceptions.instrumentation;

import datadog.exceptions.jfr.ExceptionEventSamplerBridge;
import datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionSampleEvent;
import net.bytebuddy.asm.Advice;

public class ExceptionAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.This final Exception e) {
    ExceptionSampleEvent ese = ExceptionEventSamplerBridge.sample(e);
    if (ese != null && ese.shouldCommit()) {
      ese.commit();
    }
  }
}
